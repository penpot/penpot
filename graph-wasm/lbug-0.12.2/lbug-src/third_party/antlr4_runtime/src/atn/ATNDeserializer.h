/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/ATNDeserializationOptions.h"
#include "atn/SerializedATNView.h"
#include "atn/LexerAction.h"
#include "atn/Transition.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC ATNDeserializer final {
  public:
    static constexpr size_t SERIALIZED_VERSION = 4;

    ATNDeserializer();

    explicit ATNDeserializer(ATNDeserializationOptions deserializationOptions);

    std::unique_ptr<ATN> deserialize(SerializedATNView input) const;
    void verifyATN(const ATN &atn) const;

  private:
    const ATNDeserializationOptions _deserializationOptions;
  };

} // namespace atn
} // namespace antlr4
