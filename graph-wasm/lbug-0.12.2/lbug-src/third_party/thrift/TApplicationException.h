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

#ifndef _LBUG_THRIFT_TAPPLICATIONEXCEPTION_H_
#define _LBUG_THRIFT_TAPPLICATIONEXCEPTION_H_ 1

#include "Thrift.h"

namespace lbug_apache {
namespace thrift {

namespace protocol {
class TProtocol;
}

class TApplicationException : public TException {
public:
  /**
   * Error codes for the various types of exceptions.
   */
  enum TApplicationExceptionType {
    UNKNOWN = 0,
    UNKNOWN_METHOD = 1,
    INVALID_MESSAGE_TYPE = 2,
    WRONG_METHOD_NAME = 3,
    BAD_SEQUENCE_ID = 4,
    MISSING_RESULT = 5,
    INTERNAL_ERROR = 6,
    PROTOCOL_ERROR = 7,
    INVALID_TRANSFORM = 8,
    INVALID_PROTOCOL = 9,
    UNSUPPORTED_CLIENT_TYPE = 10
  };

  TApplicationException() : TException(), type_(UNKNOWN) {}

  TApplicationException(TApplicationExceptionType type) : TException(), type_(type) {}

  TApplicationException(const std::string& message) : TException(message), type_(UNKNOWN) {}

  TApplicationException(TApplicationExceptionType type, const std::string& message)
    : TException(message), type_(type) {}

  ~TApplicationException() noexcept override = default;

  /**
   * Returns an error code that provides information about the type of error
   * that has occurred.
   *
   * @return Error code
   */
  TApplicationExceptionType getType() const { return type_; }

  const char* what() const noexcept override {
    if (message_.empty()) {
      switch (type_) {
      case UNKNOWN:
        return "TApplicationException: Unknown application exception";
      case UNKNOWN_METHOD:
        return "TApplicationException: Unknown method";
      case INVALID_MESSAGE_TYPE:
        return "TApplicationException: Invalid message type";
      case WRONG_METHOD_NAME:
        return "TApplicationException: Wrong method name";
      case BAD_SEQUENCE_ID:
        return "TApplicationException: Bad sequence identifier";
      case MISSING_RESULT:
        return "TApplicationException: Missing result";
      case INTERNAL_ERROR:
        return "TApplicationException: Internal error";
      case PROTOCOL_ERROR:
        return "TApplicationException: Protocol error";
      case INVALID_TRANSFORM:
        return "TApplicationException: Invalid transform";
      case INVALID_PROTOCOL:
        return "TApplicationException: Invalid protocol";
      case UNSUPPORTED_CLIENT_TYPE:
        return "TApplicationException: Unsupported client type";
      default:
        return "TApplicationException: (Invalid exception type)";
      };
    } else {
      return message_.c_str();
    }
  }

protected:
  /**
   * Error code
   */
  TApplicationExceptionType type_;
};
}
} // lbug_apache::thrift

#endif // #ifndef _LBUG_THRIFT_TAPPLICATIONEXCEPTION_H_
