/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_smithy_http::endpoint::error::ResolveEndpointError;
use aws_smithy_http::endpoint::{
    apply_endpoint as apply_endpoint_to_request_uri, EndpointPrefix, ResolveEndpoint,
    SharedEndpointResolver,
};
use aws_smithy_runtime_api::client::interceptors::InterceptorContext;
use aws_smithy_runtime_api::client::orchestrator::{
    BoxError, ConfigBagAccessors, EndpointResolver, EndpointResolverParams, HttpRequest,
};
use aws_smithy_runtime_api::config_bag::ConfigBag;
use aws_smithy_types::endpoint::Endpoint;
use http::header::HeaderName;
use http::{HeaderValue, Uri};
use std::fmt::Debug;
use std::str::FromStr;

#[derive(Debug, Clone)]
pub struct StaticUriEndpointResolver {
    endpoint: Uri,
}

impl StaticUriEndpointResolver {
    pub fn http_localhost(port: u16) -> Self {
        Self {
            endpoint: Uri::from_str(&format!("http://localhost:{port}"))
                .expect("all u16 values are valid ports"),
        }
    }

    pub fn uri(endpoint: Uri) -> Self {
        Self { endpoint }
    }
}

impl EndpointResolver for StaticUriEndpointResolver {
    fn resolve_endpoint(&self, _params: &EndpointResolverParams) -> Result<Endpoint, BoxError> {
        Ok(Endpoint::builder().url(self.endpoint.to_string()).build())
    }
}

/// Empty params to be used with [`StaticUriEndpointResolver`].
#[derive(Debug, Default)]
pub struct StaticUriEndpointResolverParams;

impl StaticUriEndpointResolverParams {
    /// Creates a new `StaticUriEndpointResolverParams`.
    pub fn new() -> Self {
        Self
    }
}

impl From<StaticUriEndpointResolverParams> for EndpointResolverParams {
    fn from(params: StaticUriEndpointResolverParams) -> Self {
        EndpointResolverParams::new(params)
    }
}

#[derive(Debug, Clone)]
pub struct DefaultEndpointResolver<Params> {
    inner: SharedEndpointResolver<Params>,
}

impl<Params> DefaultEndpointResolver<Params> {
    pub fn new(resolve_endpoint: SharedEndpointResolver<Params>) -> Self {
        Self {
            inner: resolve_endpoint,
        }
    }
}

impl<Params> EndpointResolver for DefaultEndpointResolver<Params>
where
    Params: Debug + Send + Sync + 'static,
{
    fn resolve_endpoint(&self, params: &EndpointResolverParams) -> Result<Endpoint, BoxError> {
        match params.get::<Params>() {
            Some(params) => Ok(self.inner.resolve_endpoint(params)?),
            None => Err(Box::new(ResolveEndpointError::message(
                "params of expected type was not present",
            ))),
        }
    }
}

pub(super) fn orchestrate_endpoint(
    ctx: &mut InterceptorContext,
    cfg: &mut ConfigBag,
) -> Result<(), BoxError> {
    let params = cfg.endpoint_resolver_params();
    let endpoint_prefix = cfg.get::<EndpointPrefix>();
    let request = ctx.request_mut();

    let endpoint_resolver = cfg.endpoint_resolver();
    let endpoint = endpoint_resolver.resolve_endpoint(params)?;
    apply_endpoint(request, &endpoint, endpoint_prefix)?;

    // Make the endpoint config available to interceptors
    cfg.put(endpoint);
    Ok(())
}

fn apply_endpoint(
    request: &mut HttpRequest,
    endpoint: &Endpoint,
    endpoint_prefix: Option<&EndpointPrefix>,
) -> Result<(), BoxError> {
    let uri: Uri = endpoint.url().parse().map_err(|err| {
        ResolveEndpointError::from_source("endpoint did not have a valid uri", err)
    })?;

    apply_endpoint_to_request_uri(request.uri_mut(), &uri, endpoint_prefix).map_err(|err| {
        ResolveEndpointError::message(format!(
            "failed to apply endpoint `{:?}` to request `{:?}`",
            uri, request,
        ))
        .with_source(Some(err.into()))
    })?;

    for (header_name, header_values) in endpoint.headers() {
        request.headers_mut().remove(header_name);
        for value in header_values {
            request.headers_mut().insert(
                HeaderName::from_str(header_name).map_err(|err| {
                    ResolveEndpointError::message("invalid header name")
                        .with_source(Some(err.into()))
                })?,
                HeaderValue::from_str(value).map_err(|err| {
                    ResolveEndpointError::message("invalid header value")
                        .with_source(Some(err.into()))
                })?,
            );
        }
    }
    Ok(())
}