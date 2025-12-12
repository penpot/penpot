/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "ANTLRInputStream.h"

namespace antlr4 {

  /// This is an ANTLRInputStream that is loaded from a file all at once
  /// when you construct the object (or call load()).
  // TODO: this class needs testing.
  class ANTLR4CPP_PUBLIC ANTLRFileStream : public ANTLRInputStream {
  public:
    ANTLRFileStream() = default;
    ANTLRFileStream(const std::string &) = delete;
    ANTLRFileStream(const char *data, size_t length) = delete;
    ANTLRFileStream(std::istream &stream) = delete;

    // Assumes a file name encoded in UTF-8 and file content in the same encoding (with or w/o BOM).
    virtual void loadFromFile(const std::string &fileName);
    virtual std::string getSourceName() const override;

  private:
    std::string _fileName; // UTF-8 encoded file name.
  };

} // namespace antlr4
