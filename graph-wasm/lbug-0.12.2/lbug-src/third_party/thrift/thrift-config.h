/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#ifndef THRIFT_CONFIG_H
#define THRIFT_CONFIG_H


#ifdef _WIN32
#if defined(_M_IX86) || defined(_M_X64) || defined(_M_ARM) || defined(_M_ARM64)
#define ARITHMETIC_RIGHT_SHIFT 1
#define SIGNED_RIGHT_SHIFT_IS 1
#endif
#else
#define SIGNED_RIGHT_SHIFT_IS  1
#define ARITHMETIC_RIGHT_SHIFT 1
#endif

#endif