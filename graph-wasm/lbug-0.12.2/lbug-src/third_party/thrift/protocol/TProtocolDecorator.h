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

#ifndef THRIFT_TPROTOCOLDECORATOR_H_
#define THRIFT_TPROTOCOLDECORATOR_H_ 1

#include "thrift/protocol/TProtocol.h"
#include <memory>
#include "duckdb/common/vector.hpp"

namespace lbug_apache {
namespace thrift {
namespace protocol {
using std::shared_ptr;

/**
 * <code>TProtocolDecorator</code> forwards all requests to an enclosed
 * <code>TProtocol</code> instance, providing a way to author concise
 * concrete decorator subclasses.
 *
 * <p>See p.175 of Design Patterns (by Gamma et al.)</p>
 *
 * @see lbug_apache::thrift::protocol::TMultiplexedProtocol
 */
class TProtocolDecorator : public TProtocol {
public:
  ~TProtocolDecorator() override = default;

  // Desc: Initializes the protocol decorator object.
  TProtocolDecorator(shared_ptr<TProtocol> proto)
    : TProtocol(proto->getTransport()), protocol(proto) {}

  uint32_t writeMessageBegin_virt(const std::string& name,
                                          const TMessageType messageType,
                                          const int32_t seqid) override {
    return protocol->writeMessageBegin(name, messageType, seqid);
  }
  uint32_t writeMessageEnd_virt() override { return protocol->writeMessageEnd(); }
  uint32_t writeStructBegin_virt(const char* name) override {
    return protocol->writeStructBegin(name);
  }
  uint32_t writeStructEnd_virt() override { return protocol->writeStructEnd(); }

  uint32_t writeFieldBegin_virt(const char* name,
                                        const TType fieldType,
                                        const int16_t fieldId) override {
    return protocol->writeFieldBegin(name, fieldType, fieldId);
  }

  uint32_t writeFieldEnd_virt() override { return protocol->writeFieldEnd(); }
  uint32_t writeFieldStop_virt() override { return protocol->writeFieldStop(); }

  uint32_t writeMapBegin_virt(const TType keyType,
                                      const TType valType,
                                      const uint32_t size) override {
    return protocol->writeMapBegin(keyType, valType, size);
  }

  uint32_t writeMapEnd_virt() override { return protocol->writeMapEnd(); }

  uint32_t writeListBegin_virt(const TType elemType, const uint32_t size) override {
    return protocol->writeListBegin(elemType, size);
  }
  uint32_t writeListEnd_virt() override { return protocol->writeListEnd(); }

  uint32_t writeSetBegin_virt(const TType elemType, const uint32_t size) override {
    return protocol->writeSetBegin(elemType, size);
  }
  uint32_t writeSetEnd_virt() override { return protocol->writeSetEnd(); }

  uint32_t writeBool_virt(const bool value) override { return protocol->writeBool(value); }
  uint32_t writeByte_virt(const int8_t byte) override { return protocol->writeByte(byte); }
  uint32_t writeI16_virt(const int16_t i16) override { return protocol->writeI16(i16); }
  uint32_t writeI32_virt(const int32_t i32) override { return protocol->writeI32(i32); }
  uint32_t writeI64_virt(const int64_t i64) override { return protocol->writeI64(i64); }

  uint32_t writeDouble_virt(const double dub) override { return protocol->writeDouble(dub); }
  uint32_t writeString_virt(const std::string& str) override { return protocol->writeString(str); }
  uint32_t writeBinary_virt(const std::string& str) override { return protocol->writeBinary(str); }

  uint32_t readMessageBegin_virt(std::string& name,
                                         TMessageType& messageType,
                                         int32_t& seqid) override {
    return protocol->readMessageBegin(name, messageType, seqid);
  }
  uint32_t readMessageEnd_virt() override { return protocol->readMessageEnd(); }

  uint32_t readStructBegin_virt(std::string& name) override {
    return protocol->readStructBegin(name);
  }
  uint32_t readStructEnd_virt() override { return protocol->readStructEnd(); }

  uint32_t readFieldBegin_virt(std::string& name, TType& fieldType, int16_t& fieldId) override {
    return protocol->readFieldBegin(name, fieldType, fieldId);
  }
  uint32_t readFieldEnd_virt() override { return protocol->readFieldEnd(); }

  uint32_t readMapBegin_virt(TType& keyType, TType& valType, uint32_t& size) override {
    return protocol->readMapBegin(keyType, valType, size);
  }
  uint32_t readMapEnd_virt() override { return protocol->readMapEnd(); }

  uint32_t readListBegin_virt(TType& elemType, uint32_t& size) override {
    return protocol->readListBegin(elemType, size);
  }
  uint32_t readListEnd_virt() override { return protocol->readListEnd(); }

  uint32_t readSetBegin_virt(TType& elemType, uint32_t& size) override {
    return protocol->readSetBegin(elemType, size);
  }
  uint32_t readSetEnd_virt() override { return protocol->readSetEnd(); }

  uint32_t readBool_virt(bool& value) override { return protocol->readBool(value); }
  uint32_t readBool_virt(lbug::vector<bool>::reference value) override {
    return protocol->readBool(value);
  }

  uint32_t readByte_virt(int8_t& byte) override { return protocol->readByte(byte); }

  uint32_t readI16_virt(int16_t& i16) override { return protocol->readI16(i16); }
  uint32_t readI32_virt(int32_t& i32) override { return protocol->readI32(i32); }
  uint32_t readI64_virt(int64_t& i64) override { return protocol->readI64(i64); }

  uint32_t readDouble_virt(double& dub) override { return protocol->readDouble(dub); }

  uint32_t readString_virt(std::string& str) override { return protocol->readString(str); }
  uint32_t readBinary_virt(std::string& str) override { return protocol->readBinary(str); }

private:
  shared_ptr<TProtocol> protocol;
};
}
}
}

#endif // THRIFT_TPROTOCOLDECORATOR_H_
