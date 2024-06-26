/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

pub(crate) trait Constrained {
    type Unconstrained;
}

#[derive(Debug, Clone)]
#[allow(dead_code)]
pub(crate) enum MaybeConstrained<T: Constrained> {
    Constrained(T),
    Unconstrained(T::Unconstrained),
}
