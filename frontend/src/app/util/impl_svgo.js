var commonjsGlobal = "undefined" != typeof globalThis ? globalThis : "undefined" != typeof window ? window : "undefined" != typeof global ? global : "undefined" != typeof self ? self : {};

function getAugmentedNamespace(n) {
  if (n.__esModule) return n;
  var f = n.default;
  if ("function" == typeof f) {
    var a = function a() {
      return this instanceof a ? Reflect.construct(f, arguments, this.constructor) : f.apply(this, arguments);
    };
    a.prototype = f.prototype;
  } else a = {};
  return Object.defineProperty(a, "__esModule", {
    value: !0
  }), Object.keys(n).forEach((function(k) {
    var d = Object.getOwnPropertyDescriptor(n, k);
    Object.defineProperty(a, k, d.get ? d : {
      enumerable: !0,
      get: function() {
        return n[k];
      }
    });
  })), a;
}

var svgo = {}, parser$4 = {}, sax = {};

!function(sax) {
  sax.parser = function(strict, opt) {
    return new SAXParser(strict, opt);
  }, sax.SAXParser = SAXParser, sax.MAX_BUFFER_LENGTH = 65536;
  var buffers = [ "comment", "sgmlDecl", "textNode", "tagName", "doctype", "procInstName", "procInstBody", "entity", "attribName", "attribValue", "cdata", "script" ];
  function SAXParser(strict, opt) {
    if (!(this instanceof SAXParser)) return new SAXParser(strict, opt);
    !function(parser) {
      for (var i = 0, l = buffers.length; i < l; i++) parser[buffers[i]] = "";
    }(this), this.q = this.c = "", this.bufferCheckPosition = sax.MAX_BUFFER_LENGTH, 
    this.opt = opt || {}, this.opt.lowercase = this.opt.lowercase || this.opt.lowercasetags, 
    this.looseCase = this.opt.lowercase ? "toLowerCase" : "toUpperCase", this.tags = [], 
    this.closed = this.closedRoot = this.sawRoot = !1, this.tag = this.error = null, 
    this.strict = !!strict, this.noscript = !(!strict && !this.opt.noscript), this.state = S.BEGIN, 
    this.strictEntities = this.opt.strictEntities, this.ENTITIES = this.strictEntities ? Object.create(sax.XML_ENTITIES) : Object.create(sax.ENTITIES), 
    this.attribList = [], this.opt.xmlns && (this.ns = Object.create(rootNS)), this.trackPosition = !1 !== this.opt.position, 
    this.trackPosition && (this.position = this.line = this.column = 0), emit(this, "onready");
  }
  sax.EVENTS = [ "text", "processinginstruction", "sgmldeclaration", "doctype", "comment", "opentagstart", "attribute", "opentag", "closetag", "opencdata", "cdata", "closecdata", "error", "end", "ready", "script", "opennamespace", "closenamespace" ], 
  Object.create || (Object.create = function(o) {
    function F() {}
    return F.prototype = o, new F;
  }), Object.keys || (Object.keys = function(o) {
    var a = [];
    for (var i in o) o.hasOwnProperty(i) && a.push(i);
    return a;
  }), SAXParser.prototype = {
    end: function() {
      end(this);
    },
    write: function(chunk) {
      if (this.error) throw this.error;
      if (this.closed) return error(this, "Cannot write after close. Assign an onready handler.");
      if (null === chunk) return end(this);
      "object" == typeof chunk && (chunk = chunk.toString());
      for (var i = 0, c = ""; c = charAt(chunk, i++), this.c = c, c; ) switch (this.trackPosition && (this.position++, 
      "\n" === c ? (this.line++, this.column = 0) : this.column++), this.state) {
       case S.BEGIN:
        if (this.state = S.BEGIN_WHITESPACE, "\ufeff" === c) continue;
        beginWhiteSpace(this, c);
        continue;

       case S.BEGIN_WHITESPACE:
        beginWhiteSpace(this, c);
        continue;

       case S.TEXT:
        if (this.sawRoot && !this.closedRoot) {
          for (var starti = i - 1; c && "<" !== c && "&" !== c; ) (c = charAt(chunk, i++)) && this.trackPosition && (this.position++, 
          "\n" === c ? (this.line++, this.column = 0) : this.column++);
          this.textNode += chunk.substring(starti, i - 1);
        }
        "<" !== c || this.sawRoot && this.closedRoot && !this.strict ? (isWhitespace(c) || this.sawRoot && !this.closedRoot || strictFail(this, "Text data outside of root node."), 
        "&" === c ? this.state = S.TEXT_ENTITY : this.textNode += c) : (this.state = S.OPEN_WAKA, 
        this.startTagPosition = this.position);
        continue;

       case S.SCRIPT:
        "<" === c ? this.state = S.SCRIPT_ENDING : this.script += c;
        continue;

       case S.SCRIPT_ENDING:
        "/" === c ? this.state = S.CLOSE_TAG : (this.script += "<" + c, this.state = S.SCRIPT);
        continue;

       case S.OPEN_WAKA:
        if ("!" === c) this.state = S.SGML_DECL, this.sgmlDecl = ""; else if (isWhitespace(c)) ; else if (isMatch(nameStart, c)) this.state = S.OPEN_TAG, 
        this.tagName = c; else if ("/" === c) this.state = S.CLOSE_TAG, this.tagName = ""; else if ("?" === c) this.state = S.PROC_INST, 
        this.procInstName = this.procInstBody = ""; else {
          if (strictFail(this, "Unencoded <"), this.startTagPosition + 1 < this.position) {
            var pad = this.position - this.startTagPosition;
            c = new Array(pad).join(" ") + c;
          }
          this.textNode += "<" + c, this.state = S.TEXT;
        }
        continue;

       case S.SGML_DECL:
        (this.sgmlDecl + c).toUpperCase() === CDATA ? (emitNode(this, "onopencdata"), this.state = S.CDATA, 
        this.sgmlDecl = "", this.cdata = "") : this.sgmlDecl + c === "--" ? (this.state = S.COMMENT, 
        this.comment = "", this.sgmlDecl = "") : (this.sgmlDecl + c).toUpperCase() === DOCTYPE ? (this.state = S.DOCTYPE, 
        (this.doctype || this.sawRoot) && strictFail(this, "Inappropriately located doctype declaration"), 
        this.doctype = "", this.sgmlDecl = "") : ">" === c ? (emitNode(this, "onsgmldeclaration", this.sgmlDecl), 
        this.sgmlDecl = "", this.state = S.TEXT) : isQuote(c) ? (this.state = S.SGML_DECL_QUOTED, 
        this.sgmlDecl += c) : this.sgmlDecl += c;
        continue;

       case S.SGML_DECL_QUOTED:
        c === this.q && (this.state = S.SGML_DECL, this.q = ""), this.sgmlDecl += c;
        continue;

       case S.DOCTYPE:
        ">" === c ? (this.state = S.TEXT, emitNode(this, "ondoctype", this.doctype), this.doctype = !0) : (this.doctype += c, 
        "[" === c ? this.state = S.DOCTYPE_DTD : isQuote(c) && (this.state = S.DOCTYPE_QUOTED, 
        this.q = c));
        continue;

       case S.DOCTYPE_QUOTED:
        this.doctype += c, c === this.q && (this.q = "", this.state = S.DOCTYPE);
        continue;

       case S.DOCTYPE_DTD:
        this.doctype += c, "]" === c ? this.state = S.DOCTYPE : isQuote(c) && (this.state = S.DOCTYPE_DTD_QUOTED, 
        this.q = c);
        continue;

       case S.DOCTYPE_DTD_QUOTED:
        this.doctype += c, c === this.q && (this.state = S.DOCTYPE_DTD, this.q = "");
        continue;

       case S.COMMENT:
        "-" === c ? this.state = S.COMMENT_ENDING : this.comment += c;
        continue;

       case S.COMMENT_ENDING:
        "-" === c ? (this.state = S.COMMENT_ENDED, this.comment = textopts(this.opt, this.comment), 
        this.comment && emitNode(this, "oncomment", this.comment), this.comment = "") : (this.comment += "-" + c, 
        this.state = S.COMMENT);
        continue;

       case S.COMMENT_ENDED:
        ">" !== c ? (strictFail(this, "Malformed comment"), this.comment += "--" + c, this.state = S.COMMENT) : this.state = S.TEXT;
        continue;

       case S.CDATA:
        "]" === c ? this.state = S.CDATA_ENDING : this.cdata += c;
        continue;

       case S.CDATA_ENDING:
        "]" === c ? this.state = S.CDATA_ENDING_2 : (this.cdata += "]" + c, this.state = S.CDATA);
        continue;

       case S.CDATA_ENDING_2:
        ">" === c ? (this.cdata && emitNode(this, "oncdata", this.cdata), emitNode(this, "onclosecdata"), 
        this.cdata = "", this.state = S.TEXT) : "]" === c ? this.cdata += "]" : (this.cdata += "]]" + c, 
        this.state = S.CDATA);
        continue;

       case S.PROC_INST:
        "?" === c ? this.state = S.PROC_INST_ENDING : isWhitespace(c) ? this.state = S.PROC_INST_BODY : this.procInstName += c;
        continue;

       case S.PROC_INST_BODY:
        if (!this.procInstBody && isWhitespace(c)) continue;
        "?" === c ? this.state = S.PROC_INST_ENDING : this.procInstBody += c;
        continue;

       case S.PROC_INST_ENDING:
        ">" === c ? (emitNode(this, "onprocessinginstruction", {
          name: this.procInstName,
          body: this.procInstBody
        }), this.procInstName = this.procInstBody = "", this.state = S.TEXT) : (this.procInstBody += "?" + c, 
        this.state = S.PROC_INST_BODY);
        continue;

       case S.OPEN_TAG:
        isMatch(nameBody, c) ? this.tagName += c : (newTag(this), ">" === c ? openTag(this) : "/" === c ? this.state = S.OPEN_TAG_SLASH : (isWhitespace(c) || strictFail(this, "Invalid character in tag name"), 
        this.state = S.ATTRIB));
        continue;

       case S.OPEN_TAG_SLASH:
        ">" === c ? (openTag(this, !0), closeTag(this)) : (strictFail(this, "Forward-slash in opening tag not followed by >"), 
        this.state = S.ATTRIB);
        continue;

       case S.ATTRIB:
        if (isWhitespace(c)) continue;
        ">" === c ? openTag(this) : "/" === c ? this.state = S.OPEN_TAG_SLASH : isMatch(nameStart, c) ? (this.attribName = c, 
        this.attribValue = "", this.state = S.ATTRIB_NAME) : strictFail(this, "Invalid attribute name");
        continue;

       case S.ATTRIB_NAME:
        "=" === c ? this.state = S.ATTRIB_VALUE : ">" === c ? (strictFail(this, "Attribute without value"), 
        this.attribValue = this.attribName, attrib(this), openTag(this)) : isWhitespace(c) ? this.state = S.ATTRIB_NAME_SAW_WHITE : isMatch(nameBody, c) ? this.attribName += c : strictFail(this, "Invalid attribute name");
        continue;

       case S.ATTRIB_NAME_SAW_WHITE:
        if ("=" === c) this.state = S.ATTRIB_VALUE; else {
          if (isWhitespace(c)) continue;
          strictFail(this, "Attribute without value"), this.tag.attributes[this.attribName] = "", 
          this.attribValue = "", emitNode(this, "onattribute", {
            name: this.attribName,
            value: ""
          }), this.attribName = "", ">" === c ? openTag(this) : isMatch(nameStart, c) ? (this.attribName = c, 
          this.state = S.ATTRIB_NAME) : (strictFail(this, "Invalid attribute name"), this.state = S.ATTRIB);
        }
        continue;

       case S.ATTRIB_VALUE:
        if (isWhitespace(c)) continue;
        isQuote(c) ? (this.q = c, this.state = S.ATTRIB_VALUE_QUOTED) : (strictFail(this, "Unquoted attribute value"), 
        this.state = S.ATTRIB_VALUE_UNQUOTED, this.attribValue = c);
        continue;

       case S.ATTRIB_VALUE_QUOTED:
        if (c !== this.q) {
          "&" === c ? this.state = S.ATTRIB_VALUE_ENTITY_Q : this.attribValue += c;
          continue;
        }
        attrib(this), this.q = "", this.state = S.ATTRIB_VALUE_CLOSED;
        continue;

       case S.ATTRIB_VALUE_CLOSED:
        isWhitespace(c) ? this.state = S.ATTRIB : ">" === c ? openTag(this) : "/" === c ? this.state = S.OPEN_TAG_SLASH : isMatch(nameStart, c) ? (strictFail(this, "No whitespace between attributes"), 
        this.attribName = c, this.attribValue = "", this.state = S.ATTRIB_NAME) : strictFail(this, "Invalid attribute name");
        continue;

       case S.ATTRIB_VALUE_UNQUOTED:
        if (!isAttribEnd(c)) {
          "&" === c ? this.state = S.ATTRIB_VALUE_ENTITY_U : this.attribValue += c;
          continue;
        }
        attrib(this), ">" === c ? openTag(this) : this.state = S.ATTRIB;
        continue;

       case S.CLOSE_TAG:
        if (this.tagName) ">" === c ? closeTag(this) : isMatch(nameBody, c) ? this.tagName += c : this.script ? (this.script += "</" + this.tagName, 
        this.tagName = "", this.state = S.SCRIPT) : (isWhitespace(c) || strictFail(this, "Invalid tagname in closing tag"), 
        this.state = S.CLOSE_TAG_SAW_WHITE); else {
          if (isWhitespace(c)) continue;
          notMatch(nameStart, c) ? this.script ? (this.script += "</" + c, this.state = S.SCRIPT) : strictFail(this, "Invalid tagname in closing tag.") : this.tagName = c;
        }
        continue;

       case S.CLOSE_TAG_SAW_WHITE:
        if (isWhitespace(c)) continue;
        ">" === c ? closeTag(this) : strictFail(this, "Invalid characters in closing tag");
        continue;

       case S.TEXT_ENTITY:
       case S.ATTRIB_VALUE_ENTITY_Q:
       case S.ATTRIB_VALUE_ENTITY_U:
        var returnState, buffer;
        switch (this.state) {
         case S.TEXT_ENTITY:
          returnState = S.TEXT, buffer = "textNode";
          break;

         case S.ATTRIB_VALUE_ENTITY_Q:
          returnState = S.ATTRIB_VALUE_QUOTED, buffer = "attribValue";
          break;

         case S.ATTRIB_VALUE_ENTITY_U:
          returnState = S.ATTRIB_VALUE_UNQUOTED, buffer = "attribValue";
        }
        if (";" === c) {
          var parsedEntity = parseEntity(this);
          this.state !== S.TEXT_ENTITY || sax.ENTITIES[this.entity] || parsedEntity === "&" + this.entity + ";" ? this[buffer] += parsedEntity : chunk = chunk.slice(0, i) + parsedEntity + chunk.slice(i), 
          this.entity = "", this.state = returnState;
        } else isMatch(this.entity.length ? entityBody : entityStart, c) ? this.entity += c : (strictFail(this, "Invalid character in entity name"), 
        this[buffer] += "&" + this.entity + c, this.entity = "", this.state = returnState);
        continue;

       default:
        throw new Error(this, "Unknown state: " + this.state);
      }
      return this.position >= this.bufferCheckPosition && function(parser) {
        for (var maxAllowed = Math.max(sax.MAX_BUFFER_LENGTH, 10), maxActual = 0, i = 0, l = buffers.length; i < l; i++) {
          var len = parser[buffers[i]].length;
          if (len > maxAllowed) switch (buffers[i]) {
           case "textNode":
            closeText(parser);
            break;

           case "cdata":
            emitNode(parser, "oncdata", parser.cdata), parser.cdata = "";
            break;

           case "script":
            emitNode(parser, "onscript", parser.script), parser.script = "";
            break;

           default:
            error(parser, "Max buffer length exceeded: " + buffers[i]);
          }
          maxActual = Math.max(maxActual, len);
        }
        var m = sax.MAX_BUFFER_LENGTH - maxActual;
        parser.bufferCheckPosition = m + parser.position;
      }(this), this;
    },
    resume: function() {
      return this.error = null, this;
    },
    close: function() {
      return this.write(null);
    },
    flush: function() {
      !function(parser) {
        closeText(parser), "" !== parser.cdata && (emitNode(parser, "oncdata", parser.cdata), 
        parser.cdata = ""), "" !== parser.script && (emitNode(parser, "onscript", parser.script), 
        parser.script = "");
      }(this);
    }
  };
  var CDATA = "[CDATA[", DOCTYPE = "DOCTYPE", XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace", XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/", rootNS = {
    xml: XML_NAMESPACE,
    xmlns: XMLNS_NAMESPACE
  }, nameStart = /[:_A-Za-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02FF\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD]/, nameBody = /[:_A-Za-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02FF\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD\u00B7\u0300-\u036F\u203F-\u2040.\d-]/, entityStart = /[#:_A-Za-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02FF\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD]/, entityBody = /[#:_A-Za-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02FF\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD\u00B7\u0300-\u036F\u203F-\u2040.\d-]/;
  function isWhitespace(c) {
    return " " === c || "\n" === c || "\r" === c || "\t" === c;
  }
  function isQuote(c) {
    return '"' === c || "'" === c;
  }
  function isAttribEnd(c) {
    return ">" === c || isWhitespace(c);
  }
  function isMatch(regex, c) {
    return regex.test(c);
  }
  function notMatch(regex, c) {
    return !isMatch(regex, c);
  }
  var S = 0;
  for (var s in sax.STATE = {
    BEGIN: S++,
    BEGIN_WHITESPACE: S++,
    TEXT: S++,
    TEXT_ENTITY: S++,
    OPEN_WAKA: S++,
    SGML_DECL: S++,
    SGML_DECL_QUOTED: S++,
    DOCTYPE: S++,
    DOCTYPE_QUOTED: S++,
    DOCTYPE_DTD: S++,
    DOCTYPE_DTD_QUOTED: S++,
    COMMENT_STARTING: S++,
    COMMENT: S++,
    COMMENT_ENDING: S++,
    COMMENT_ENDED: S++,
    CDATA: S++,
    CDATA_ENDING: S++,
    CDATA_ENDING_2: S++,
    PROC_INST: S++,
    PROC_INST_BODY: S++,
    PROC_INST_ENDING: S++,
    OPEN_TAG: S++,
    OPEN_TAG_SLASH: S++,
    ATTRIB: S++,
    ATTRIB_NAME: S++,
    ATTRIB_NAME_SAW_WHITE: S++,
    ATTRIB_VALUE: S++,
    ATTRIB_VALUE_QUOTED: S++,
    ATTRIB_VALUE_CLOSED: S++,
    ATTRIB_VALUE_UNQUOTED: S++,
    ATTRIB_VALUE_ENTITY_Q: S++,
    ATTRIB_VALUE_ENTITY_U: S++,
    CLOSE_TAG: S++,
    CLOSE_TAG_SAW_WHITE: S++,
    SCRIPT: S++,
    SCRIPT_ENDING: S++
  }, sax.XML_ENTITIES = {
    "amp": "&",
    "gt": ">",
    "lt": "<",
    "quot": '"',
    "apos": "'"
  }, sax.ENTITIES = {
    "amp": "&",
    "gt": ">",
    "lt": "<",
    "quot": '"',
    "apos": "'",
    "AElig": 198,
    "Aacute": 193,
    "Acirc": 194,
    "Agrave": 192,
    "Aring": 197,
    "Atilde": 195,
    "Auml": 196,
    "Ccedil": 199,
    "ETH": 208,
    "Eacute": 201,
    "Ecirc": 202,
    "Egrave": 200,
    "Euml": 203,
    "Iacute": 205,
    "Icirc": 206,
    "Igrave": 204,
    "Iuml": 207,
    "Ntilde": 209,
    "Oacute": 211,
    "Ocirc": 212,
    "Ograve": 210,
    "Oslash": 216,
    "Otilde": 213,
    "Ouml": 214,
    "THORN": 222,
    "Uacute": 218,
    "Ucirc": 219,
    "Ugrave": 217,
    "Uuml": 220,
    "Yacute": 221,
    "aacute": 225,
    "acirc": 226,
    "aelig": 230,
    "agrave": 224,
    "aring": 229,
    "atilde": 227,
    "auml": 228,
    "ccedil": 231,
    "eacute": 233,
    "ecirc": 234,
    "egrave": 232,
    "eth": 240,
    "euml": 235,
    "iacute": 237,
    "icirc": 238,
    "igrave": 236,
    "iuml": 239,
    "ntilde": 241,
    "oacute": 243,
    "ocirc": 244,
    "ograve": 242,
    "oslash": 248,
    "otilde": 245,
    "ouml": 246,
    "szlig": 223,
    "thorn": 254,
    "uacute": 250,
    "ucirc": 251,
    "ugrave": 249,
    "uuml": 252,
    "yacute": 253,
    "yuml": 255,
    "copy": 169,
    "reg": 174,
    "nbsp": 160,
    "iexcl": 161,
    "cent": 162,
    "pound": 163,
    "curren": 164,
    "yen": 165,
    "brvbar": 166,
    "sect": 167,
    "uml": 168,
    "ordf": 170,
    "laquo": 171,
    "not": 172,
    "shy": 173,
    "macr": 175,
    "deg": 176,
    "plusmn": 177,
    "sup1": 185,
    "sup2": 178,
    "sup3": 179,
    "acute": 180,
    "micro": 181,
    "para": 182,
    "middot": 183,
    "cedil": 184,
    "ordm": 186,
    "raquo": 187,
    "frac14": 188,
    "frac12": 189,
    "frac34": 190,
    "iquest": 191,
    "times": 215,
    "divide": 247,
    "OElig": 338,
    "oelig": 339,
    "Scaron": 352,
    "scaron": 353,
    "Yuml": 376,
    "fnof": 402,
    "circ": 710,
    "tilde": 732,
    "Alpha": 913,
    "Beta": 914,
    "Gamma": 915,
    "Delta": 916,
    "Epsilon": 917,
    "Zeta": 918,
    "Eta": 919,
    "Theta": 920,
    "Iota": 921,
    "Kappa": 922,
    "Lambda": 923,
    "Mu": 924,
    "Nu": 925,
    "Xi": 926,
    "Omicron": 927,
    "Pi": 928,
    "Rho": 929,
    "Sigma": 931,
    "Tau": 932,
    "Upsilon": 933,
    "Phi": 934,
    "Chi": 935,
    "Psi": 936,
    "Omega": 937,
    "alpha": 945,
    "beta": 946,
    "gamma": 947,
    "delta": 948,
    "epsilon": 949,
    "zeta": 950,
    "eta": 951,
    "theta": 952,
    "iota": 953,
    "kappa": 954,
    "lambda": 955,
    "mu": 956,
    "nu": 957,
    "xi": 958,
    "omicron": 959,
    "pi": 960,
    "rho": 961,
    "sigmaf": 962,
    "sigma": 963,
    "tau": 964,
    "upsilon": 965,
    "phi": 966,
    "chi": 967,
    "psi": 968,
    "omega": 969,
    "thetasym": 977,
    "upsih": 978,
    "piv": 982,
    "ensp": 8194,
    "emsp": 8195,
    "thinsp": 8201,
    "zwnj": 8204,
    "zwj": 8205,
    "lrm": 8206,
    "rlm": 8207,
    "ndash": 8211,
    "mdash": 8212,
    "lsquo": 8216,
    "rsquo": 8217,
    "sbquo": 8218,
    "ldquo": 8220,
    "rdquo": 8221,
    "bdquo": 8222,
    "dagger": 8224,
    "Dagger": 8225,
    "bull": 8226,
    "hellip": 8230,
    "permil": 8240,
    "prime": 8242,
    "Prime": 8243,
    "lsaquo": 8249,
    "rsaquo": 8250,
    "oline": 8254,
    "frasl": 8260,
    "euro": 8364,
    "image": 8465,
    "weierp": 8472,
    "real": 8476,
    "trade": 8482,
    "alefsym": 8501,
    "larr": 8592,
    "uarr": 8593,
    "rarr": 8594,
    "darr": 8595,
    "harr": 8596,
    "crarr": 8629,
    "lArr": 8656,
    "uArr": 8657,
    "rArr": 8658,
    "dArr": 8659,
    "hArr": 8660,
    "forall": 8704,
    "part": 8706,
    "exist": 8707,
    "empty": 8709,
    "nabla": 8711,
    "isin": 8712,
    "notin": 8713,
    "ni": 8715,
    "prod": 8719,
    "sum": 8721,
    "minus": 8722,
    "lowast": 8727,
    "radic": 8730,
    "prop": 8733,
    "infin": 8734,
    "ang": 8736,
    "and": 8743,
    "or": 8744,
    "cap": 8745,
    "cup": 8746,
    "int": 8747,
    "there4": 8756,
    "sim": 8764,
    "cong": 8773,
    "asymp": 8776,
    "ne": 8800,
    "equiv": 8801,
    "le": 8804,
    "ge": 8805,
    "sub": 8834,
    "sup": 8835,
    "nsub": 8836,
    "sube": 8838,
    "supe": 8839,
    "oplus": 8853,
    "otimes": 8855,
    "perp": 8869,
    "sdot": 8901,
    "lceil": 8968,
    "rceil": 8969,
    "lfloor": 8970,
    "rfloor": 8971,
    "lang": 9001,
    "rang": 9002,
    "loz": 9674,
    "spades": 9824,
    "clubs": 9827,
    "hearts": 9829,
    "diams": 9830
  }, Object.keys(sax.ENTITIES).forEach((function(key) {
    var e = sax.ENTITIES[key], s = "number" == typeof e ? String.fromCharCode(e) : e;
    sax.ENTITIES[key] = s;
  })), sax.STATE) sax.STATE[sax.STATE[s]] = s;
  function emit(parser, event, data) {
    parser[event] && parser[event](data);
  }
  function emitNode(parser, nodeType, data) {
    parser.textNode && closeText(parser), emit(parser, nodeType, data);
  }
  function closeText(parser) {
    parser.textNode = textopts(parser.opt, parser.textNode), parser.textNode && emit(parser, "ontext", parser.textNode), 
    parser.textNode = "";
  }
  function textopts(opt, text) {
    return opt.trim && (text = text.trim()), opt.normalize && (text = text.replace(/\s+/g, " ")), 
    text;
  }
  function error(parser, reason) {
    closeText(parser);
    const message = reason + "\nLine: " + parser.line + "\nColumn: " + parser.column + "\nChar: " + parser.c, error = new Error(message);
    return error.reason = reason, error.line = parser.line, error.column = parser.column, 
    parser.error = error, emit(parser, "onerror", error), parser;
  }
  function end(parser) {
    return parser.sawRoot && !parser.closedRoot && strictFail(parser, "Unclosed root tag"), 
    parser.state !== S.BEGIN && parser.state !== S.BEGIN_WHITESPACE && parser.state !== S.TEXT && error(parser, "Unexpected end"), 
    closeText(parser), parser.c = "", parser.closed = !0, emit(parser, "onend"), SAXParser.call(parser, parser.strict, parser.opt), 
    parser;
  }
  function strictFail(parser, message) {
    if ("object" != typeof parser || !(parser instanceof SAXParser)) throw new Error("bad call to strictFail");
    parser.strict && error(parser, message);
  }
  function newTag(parser) {
    parser.strict || (parser.tagName = parser.tagName[parser.looseCase]());
    var parent = parser.tags[parser.tags.length - 1] || parser, tag = parser.tag = {
      name: parser.tagName,
      attributes: {}
    };
    parser.opt.xmlns && (tag.ns = parent.ns), parser.attribList.length = 0, emitNode(parser, "onopentagstart", tag);
  }
  function qname(name, attribute) {
    var qualName = name.indexOf(":") < 0 ? [ "", name ] : name.split(":"), prefix = qualName[0], local = qualName[1];
    return attribute && "xmlns" === name && (prefix = "xmlns", local = ""), {
      prefix,
      local
    };
  }
  function attrib(parser) {
    if (parser.strict || (parser.attribName = parser.attribName[parser.looseCase]()), 
    -1 !== parser.attribList.indexOf(parser.attribName) || parser.tag.attributes.hasOwnProperty(parser.attribName)) parser.attribName = parser.attribValue = ""; else {
      if (parser.opt.xmlns) {
        var qn = qname(parser.attribName, !0), prefix = qn.prefix, local = qn.local;
        if ("xmlns" === prefix) if ("xml" === local && parser.attribValue !== XML_NAMESPACE) strictFail(parser, "xml: prefix must be bound to " + XML_NAMESPACE + "\nActual: " + parser.attribValue); else if ("xmlns" === local && parser.attribValue !== XMLNS_NAMESPACE) strictFail(parser, "xmlns: prefix must be bound to " + XMLNS_NAMESPACE + "\nActual: " + parser.attribValue); else {
          var tag = parser.tag, parent = parser.tags[parser.tags.length - 1] || parser;
          tag.ns === parent.ns && (tag.ns = Object.create(parent.ns)), tag.ns[local] = parser.attribValue;
        }
        parser.attribList.push([ parser.attribName, parser.attribValue ]);
      } else parser.tag.attributes[parser.attribName] = parser.attribValue, emitNode(parser, "onattribute", {
        name: parser.attribName,
        value: parser.attribValue
      });
      parser.attribName = parser.attribValue = "";
    }
  }
  function openTag(parser, selfClosing) {
    if (parser.opt.xmlns) {
      var tag = parser.tag, qn = qname(parser.tagName);
      tag.prefix = qn.prefix, tag.local = qn.local, tag.uri = tag.ns[qn.prefix] || "", 
      tag.prefix && !tag.uri && (strictFail(parser, "Unbound namespace prefix: " + JSON.stringify(parser.tagName)), 
      tag.uri = qn.prefix);
      var parent = parser.tags[parser.tags.length - 1] || parser;
      tag.ns && parent.ns !== tag.ns && Object.keys(tag.ns).forEach((function(p) {
        emitNode(parser, "onopennamespace", {
          prefix: p,
          uri: tag.ns[p]
        });
      }));
      for (var i = 0, l = parser.attribList.length; i < l; i++) {
        var nv = parser.attribList[i], name = nv[0], value = nv[1], qualName = qname(name, !0), prefix = qualName.prefix, local = qualName.local, uri = "" === prefix ? "" : tag.ns[prefix] || "", a = {
          name,
          value,
          prefix,
          local,
          uri
        };
        prefix && "xmlns" !== prefix && !uri && (strictFail(parser, "Unbound namespace prefix: " + JSON.stringify(prefix)), 
        a.uri = prefix), parser.tag.attributes[name] = a, emitNode(parser, "onattribute", a);
      }
      parser.attribList.length = 0;
    }
    parser.tag.isSelfClosing = !!selfClosing, parser.sawRoot = !0, parser.tags.push(parser.tag), 
    emitNode(parser, "onopentag", parser.tag), selfClosing || (parser.noscript || "script" !== parser.tagName.toLowerCase() ? parser.state = S.TEXT : parser.state = S.SCRIPT, 
    parser.tag = null, parser.tagName = ""), parser.attribName = parser.attribValue = "", 
    parser.attribList.length = 0;
  }
  function closeTag(parser) {
    if (!parser.tagName) return strictFail(parser, "Weird empty close tag."), parser.textNode += "</>", 
    void (parser.state = S.TEXT);
    if (parser.script) {
      if ("script" !== parser.tagName) return parser.script += "</" + parser.tagName + ">", 
      parser.tagName = "", void (parser.state = S.SCRIPT);
      emitNode(parser, "onscript", parser.script), parser.script = "";
    }
    var t = parser.tags.length, tagName = parser.tagName;
    parser.strict || (tagName = tagName[parser.looseCase]());
    for (var closeTo = tagName; t-- && parser.tags[t].name !== closeTo; ) strictFail(parser, "Unexpected close tag");
    if (t < 0) return strictFail(parser, "Unmatched closing tag: " + parser.tagName), 
    parser.textNode += "</" + parser.tagName + ">", void (parser.state = S.TEXT);
    parser.tagName = tagName;
    for (var s = parser.tags.length; s-- > t; ) {
      var tag = parser.tag = parser.tags.pop();
      parser.tagName = parser.tag.name, emitNode(parser, "onclosetag", parser.tagName);
      var x = {};
      for (var i in tag.ns) x[i] = tag.ns[i];
      var parent = parser.tags[parser.tags.length - 1] || parser;
      parser.opt.xmlns && tag.ns !== parent.ns && Object.keys(tag.ns).forEach((function(p) {
        var n = tag.ns[p];
        emitNode(parser, "onclosenamespace", {
          prefix: p,
          uri: n
        });
      }));
    }
    0 === t && (parser.closedRoot = !0), parser.tagName = parser.attribValue = parser.attribName = "", 
    parser.attribList.length = 0, parser.state = S.TEXT;
  }
  function parseEntity(parser) {
    var num, entity = parser.entity, entityLC = entity.toLowerCase(), numStr = "";
    return parser.ENTITIES[entity] ? parser.ENTITIES[entity] : parser.ENTITIES[entityLC] ? parser.ENTITIES[entityLC] : ("#" === (entity = entityLC).charAt(0) && ("x" === entity.charAt(1) ? (entity = entity.slice(2), 
    numStr = (num = parseInt(entity, 16)).toString(16)) : (entity = entity.slice(1), 
    numStr = (num = parseInt(entity, 10)).toString(10))), entity = entity.replace(/^0+/, ""), 
    isNaN(num) || numStr.toLowerCase() !== entity ? (strictFail(parser, "Invalid character entity"), 
    "&" + parser.entity + ";") : String.fromCodePoint(num));
  }
  function beginWhiteSpace(parser, c) {
    "<" === c ? (parser.state = S.OPEN_WAKA, parser.startTagPosition = parser.position) : isWhitespace(c) || (strictFail(parser, "Non-whitespace before first tag."), 
    parser.textNode = c, parser.state = S.TEXT);
  }
  function charAt(chunk, i) {
    var result = "";
    return i < chunk.length && (result = chunk.charAt(i)), result;
  }
  S = sax.STATE;
}(sax);

var exports, _collections = {};

(exports = _collections).elemsGroups = {
  animation: [ "animate", "animateColor", "animateMotion", "animateTransform", "set" ],
  descriptive: [ "desc", "metadata", "title" ],
  shape: [ "circle", "ellipse", "line", "path", "polygon", "polyline", "rect" ],
  structural: [ "defs", "g", "svg", "symbol", "use" ],
  paintServer: [ "solidColor", "linearGradient", "radialGradient", "meshGradient", "pattern", "hatch" ],
  nonRendering: [ "linearGradient", "radialGradient", "pattern", "clipPath", "mask", "marker", "symbol", "filter", "solidColor" ],
  container: [ "a", "defs", "g", "marker", "mask", "missing-glyph", "pattern", "svg", "switch", "symbol", "foreignObject" ],
  textContent: [ "altGlyph", "altGlyphDef", "altGlyphItem", "glyph", "glyphRef", "textPath", "text", "tref", "tspan" ],
  textContentChild: [ "altGlyph", "textPath", "tref", "tspan" ],
  lightSource: [ "feDiffuseLighting", "feSpecularLighting", "feDistantLight", "fePointLight", "feSpotLight" ],
  filterPrimitive: [ "feBlend", "feColorMatrix", "feComponentTransfer", "feComposite", "feConvolveMatrix", "feDiffuseLighting", "feDisplacementMap", "feDropShadow", "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR", "feGaussianBlur", "feImage", "feMerge", "feMergeNode", "feMorphology", "feOffset", "feSpecularLighting", "feTile", "feTurbulence" ]
}, exports.textElems = exports.elemsGroups.textContent.concat("title"), exports.pathElems = [ "path", "glyph", "missing-glyph" ], 
exports.attrsGroups = {
  animationAddition: [ "additive", "accumulate" ],
  animationAttributeTarget: [ "attributeType", "attributeName" ],
  animationEvent: [ "onbegin", "onend", "onrepeat", "onload" ],
  animationTiming: [ "begin", "dur", "end", "min", "max", "restart", "repeatCount", "repeatDur", "fill" ],
  animationValue: [ "calcMode", "values", "keyTimes", "keySplines", "from", "to", "by" ],
  conditionalProcessing: [ "requiredFeatures", "requiredExtensions", "systemLanguage" ],
  core: [ "id", "tabindex", "xml:base", "xml:lang", "xml:space" ],
  graphicalEvent: [ "onfocusin", "onfocusout", "onactivate", "onclick", "onmousedown", "onmouseup", "onmouseover", "onmousemove", "onmouseout", "onload" ],
  presentation: [ "alignment-baseline", "baseline-shift", "clip", "clip-path", "clip-rule", "color", "color-interpolation", "color-interpolation-filters", "color-profile", "color-rendering", "cursor", "direction", "display", "dominant-baseline", "enable-background", "fill", "fill-opacity", "fill-rule", "filter", "flood-color", "flood-opacity", "font-family", "font-size", "font-size-adjust", "font-stretch", "font-style", "font-variant", "font-weight", "glyph-orientation-horizontal", "glyph-orientation-vertical", "image-rendering", "letter-spacing", "lighting-color", "marker-end", "marker-mid", "marker-start", "mask", "opacity", "overflow", "paint-order", "pointer-events", "shape-rendering", "stop-color", "stop-opacity", "stroke", "stroke-dasharray", "stroke-dashoffset", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-opacity", "stroke-width", "text-anchor", "text-decoration", "text-overflow", "text-rendering", "transform", "transform-origin", "unicode-bidi", "vector-effect", "visibility", "word-spacing", "writing-mode" ],
  xlink: [ "xlink:href", "xlink:show", "xlink:actuate", "xlink:type", "xlink:role", "xlink:arcrole", "xlink:title" ],
  documentEvent: [ "onunload", "onabort", "onerror", "onresize", "onscroll", "onzoom" ],
  filterPrimitive: [ "x", "y", "width", "height", "result" ],
  transferFunction: [ "type", "tableValues", "slope", "intercept", "amplitude", "exponent", "offset" ]
}, exports.attrsGroupsDefaults = {
  core: {
    "xml:space": "default"
  },
  presentation: {
    clip: "auto",
    "clip-path": "none",
    "clip-rule": "nonzero",
    mask: "none",
    opacity: "1",
    "stop-color": "#000",
    "stop-opacity": "1",
    "fill-opacity": "1",
    "fill-rule": "nonzero",
    fill: "#000",
    stroke: "none",
    "stroke-width": "1",
    "stroke-linecap": "butt",
    "stroke-linejoin": "miter",
    "stroke-miterlimit": "4",
    "stroke-dasharray": "none",
    "stroke-dashoffset": "0",
    "stroke-opacity": "1",
    "paint-order": "normal",
    "vector-effect": "none",
    display: "inline",
    visibility: "visible",
    "marker-start": "none",
    "marker-mid": "none",
    "marker-end": "none",
    "color-interpolation": "sRGB",
    "color-interpolation-filters": "linearRGB",
    "color-rendering": "auto",
    "shape-rendering": "auto",
    "text-rendering": "auto",
    "image-rendering": "auto",
    "font-style": "normal",
    "font-variant": "normal",
    "font-weight": "normal",
    "font-stretch": "normal",
    "font-size": "medium",
    "font-size-adjust": "none",
    kerning: "auto",
    "letter-spacing": "normal",
    "word-spacing": "normal",
    "text-decoration": "none",
    "text-anchor": "start",
    "text-overflow": "clip",
    "writing-mode": "lr-tb",
    "glyph-orientation-vertical": "auto",
    "glyph-orientation-horizontal": "0deg",
    direction: "ltr",
    "unicode-bidi": "normal",
    "dominant-baseline": "auto",
    "alignment-baseline": "baseline",
    "baseline-shift": "baseline"
  },
  transferFunction: {
    slope: "1",
    intercept: "0",
    amplitude: "1",
    exponent: "1",
    offset: "0"
  }
}, exports.elems = {
  a: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "target" ],
    defaults: {
      target: "_self"
    },
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view", "tspan" ]
  },
  altGlyph: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "x", "y", "dx", "dy", "glyphRef", "format", "rotate" ]
  },
  altGlyphDef: {
    attrsGroups: [ "core" ],
    content: [ "glyphRef" ]
  },
  altGlyphItem: {
    attrsGroups: [ "core" ],
    content: [ "glyphRef", "altGlyphItem" ]
  },
  animate: {
    attrsGroups: [ "conditionalProcessing", "core", "animationAddition", "animationAttributeTarget", "animationEvent", "animationTiming", "animationValue", "presentation", "xlink" ],
    attrs: [ "externalResourcesRequired" ],
    contentGroups: [ "descriptive" ]
  },
  animateColor: {
    attrsGroups: [ "conditionalProcessing", "core", "animationEvent", "xlink", "animationAttributeTarget", "animationTiming", "animationValue", "animationAddition", "presentation" ],
    attrs: [ "externalResourcesRequired" ],
    contentGroups: [ "descriptive" ]
  },
  animateMotion: {
    attrsGroups: [ "conditionalProcessing", "core", "animationEvent", "xlink", "animationTiming", "animationValue", "animationAddition" ],
    attrs: [ "externalResourcesRequired", "path", "keyPoints", "rotate", "origin" ],
    defaults: {
      rotate: "0"
    },
    contentGroups: [ "descriptive" ],
    content: [ "mpath" ]
  },
  animateTransform: {
    attrsGroups: [ "conditionalProcessing", "core", "animationEvent", "xlink", "animationAttributeTarget", "animationTiming", "animationValue", "animationAddition" ],
    attrs: [ "externalResourcesRequired", "type" ],
    contentGroups: [ "descriptive" ]
  },
  circle: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "cx", "cy", "r" ],
    defaults: {
      cx: "0",
      cy: "0"
    },
    contentGroups: [ "animation", "descriptive" ]
  },
  clipPath: {
    attrsGroups: [ "conditionalProcessing", "core", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "clipPathUnits" ],
    defaults: {
      clipPathUnits: "userSpaceOnUse"
    },
    contentGroups: [ "animation", "descriptive", "shape" ],
    content: [ "text", "use" ]
  },
  "color-profile": {
    attrsGroups: [ "core", "xlink" ],
    attrs: [ "local", "name", "rendering-intent" ],
    defaults: {
      name: "sRGB",
      "rendering-intent": "auto"
    },
    contentGroups: [ "descriptive" ]
  },
  cursor: {
    attrsGroups: [ "core", "conditionalProcessing", "xlink" ],
    attrs: [ "externalResourcesRequired", "x", "y" ],
    defaults: {
      x: "0",
      y: "0"
    },
    contentGroups: [ "descriptive" ]
  },
  defs: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform" ],
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  desc: {
    attrsGroups: [ "core" ],
    attrs: [ "class", "style" ]
  },
  ellipse: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "cx", "cy", "rx", "ry" ],
    defaults: {
      cx: "0",
      cy: "0"
    },
    contentGroups: [ "animation", "descriptive" ]
  },
  feBlend: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "in2", "mode" ],
    defaults: {
      mode: "normal"
    },
    content: [ "animate", "set" ]
  },
  feColorMatrix: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "type", "values" ],
    defaults: {
      type: "matrix"
    },
    content: [ "animate", "set" ]
  },
  feComponentTransfer: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in" ],
    content: [ "feFuncA", "feFuncB", "feFuncG", "feFuncR" ]
  },
  feComposite: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "in2", "operator", "k1", "k2", "k3", "k4" ],
    defaults: {
      operator: "over",
      k1: "0",
      k2: "0",
      k3: "0",
      k4: "0"
    },
    content: [ "animate", "set" ]
  },
  feConvolveMatrix: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "order", "kernelMatrix", "divisor", "bias", "targetX", "targetY", "edgeMode", "kernelUnitLength", "preserveAlpha" ],
    defaults: {
      order: "3",
      bias: "0",
      edgeMode: "duplicate",
      preserveAlpha: "false"
    },
    content: [ "animate", "set" ]
  },
  feDiffuseLighting: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "surfaceScale", "diffuseConstant", "kernelUnitLength" ],
    defaults: {
      surfaceScale: "1",
      diffuseConstant: "1"
    },
    contentGroups: [ "descriptive" ],
    content: [ "feDistantLight", "fePointLight", "feSpotLight" ]
  },
  feDisplacementMap: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "in2", "scale", "xChannelSelector", "yChannelSelector" ],
    defaults: {
      scale: "0",
      xChannelSelector: "A",
      yChannelSelector: "A"
    },
    content: [ "animate", "set" ]
  },
  feDistantLight: {
    attrsGroups: [ "core" ],
    attrs: [ "azimuth", "elevation" ],
    defaults: {
      azimuth: "0",
      elevation: "0"
    },
    content: [ "animate", "set" ]
  },
  feFlood: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style" ],
    content: [ "animate", "animateColor", "set" ]
  },
  feFuncA: {
    attrsGroups: [ "core", "transferFunction" ],
    content: [ "set", "animate" ]
  },
  feFuncB: {
    attrsGroups: [ "core", "transferFunction" ],
    content: [ "set", "animate" ]
  },
  feFuncG: {
    attrsGroups: [ "core", "transferFunction" ],
    content: [ "set", "animate" ]
  },
  feFuncR: {
    attrsGroups: [ "core", "transferFunction" ],
    content: [ "set", "animate" ]
  },
  feGaussianBlur: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "stdDeviation" ],
    defaults: {
      stdDeviation: "0"
    },
    content: [ "set", "animate" ]
  },
  feImage: {
    attrsGroups: [ "core", "presentation", "filterPrimitive", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "preserveAspectRatio", "href", "xlink:href" ],
    defaults: {
      preserveAspectRatio: "xMidYMid meet"
    },
    content: [ "animate", "animateTransform", "set" ]
  },
  feMerge: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style" ],
    content: [ "feMergeNode" ]
  },
  feMergeNode: {
    attrsGroups: [ "core" ],
    attrs: [ "in" ],
    content: [ "animate", "set" ]
  },
  feMorphology: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "operator", "radius" ],
    defaults: {
      operator: "erode",
      radius: "0"
    },
    content: [ "animate", "set" ]
  },
  feOffset: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "dx", "dy" ],
    defaults: {
      dx: "0",
      dy: "0"
    },
    content: [ "animate", "set" ]
  },
  fePointLight: {
    attrsGroups: [ "core" ],
    attrs: [ "x", "y", "z" ],
    defaults: {
      x: "0",
      y: "0",
      z: "0"
    },
    content: [ "animate", "set" ]
  },
  feSpecularLighting: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in", "surfaceScale", "specularConstant", "specularExponent", "kernelUnitLength" ],
    defaults: {
      surfaceScale: "1",
      specularConstant: "1",
      specularExponent: "1"
    },
    contentGroups: [ "descriptive", "lightSource" ]
  },
  feSpotLight: {
    attrsGroups: [ "core" ],
    attrs: [ "x", "y", "z", "pointsAtX", "pointsAtY", "pointsAtZ", "specularExponent", "limitingConeAngle" ],
    defaults: {
      x: "0",
      y: "0",
      z: "0",
      pointsAtX: "0",
      pointsAtY: "0",
      pointsAtZ: "0",
      specularExponent: "1"
    },
    content: [ "animate", "set" ]
  },
  feTile: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "in" ],
    content: [ "animate", "set" ]
  },
  feTurbulence: {
    attrsGroups: [ "core", "presentation", "filterPrimitive" ],
    attrs: [ "class", "style", "baseFrequency", "numOctaves", "seed", "stitchTiles", "type" ],
    defaults: {
      baseFrequency: "0",
      numOctaves: "1",
      seed: "0",
      stitchTiles: "noStitch",
      type: "turbulence"
    },
    content: [ "animate", "set" ]
  },
  filter: {
    attrsGroups: [ "core", "presentation", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "x", "y", "width", "height", "filterRes", "filterUnits", "primitiveUnits", "href", "xlink:href" ],
    defaults: {
      primitiveUnits: "userSpaceOnUse",
      x: "-10%",
      y: "-10%",
      width: "120%",
      height: "120%"
    },
    contentGroups: [ "descriptive", "filterPrimitive" ],
    content: [ "animate", "set" ]
  },
  font: {
    attrsGroups: [ "core", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "horiz-origin-x", "horiz-origin-y", "horiz-adv-x", "vert-origin-x", "vert-origin-y", "vert-adv-y" ],
    defaults: {
      "horiz-origin-x": "0",
      "horiz-origin-y": "0"
    },
    contentGroups: [ "descriptive" ],
    content: [ "font-face", "glyph", "hkern", "missing-glyph", "vkern" ]
  },
  "font-face": {
    attrsGroups: [ "core" ],
    attrs: [ "font-family", "font-style", "font-variant", "font-weight", "font-stretch", "font-size", "unicode-range", "units-per-em", "panose-1", "stemv", "stemh", "slope", "cap-height", "x-height", "accent-height", "ascent", "descent", "widths", "bbox", "ideographic", "alphabetic", "mathematical", "hanging", "v-ideographic", "v-alphabetic", "v-mathematical", "v-hanging", "underline-position", "underline-thickness", "strikethrough-position", "strikethrough-thickness", "overline-position", "overline-thickness" ],
    defaults: {
      "font-style": "all",
      "font-variant": "normal",
      "font-weight": "all",
      "font-stretch": "normal",
      "unicode-range": "U+0-10FFFF",
      "units-per-em": "1000",
      "panose-1": "0 0 0 0 0 0 0 0 0 0",
      slope: "0"
    },
    contentGroups: [ "descriptive" ],
    content: [ "font-face-src" ]
  },
  "font-face-format": {
    attrsGroups: [ "core" ],
    attrs: [ "string" ]
  },
  "font-face-name": {
    attrsGroups: [ "core" ],
    attrs: [ "name" ]
  },
  "font-face-src": {
    attrsGroups: [ "core" ],
    content: [ "font-face-name", "font-face-uri" ]
  },
  "font-face-uri": {
    attrsGroups: [ "core", "xlink" ],
    attrs: [ "href", "xlink:href" ],
    content: [ "font-face-format" ]
  },
  foreignObject: {
    attrsGroups: [ "core", "conditionalProcessing", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "x", "y", "width", "height" ],
    defaults: {
      x: "0",
      y: "0"
    }
  },
  g: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform" ],
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  glyph: {
    attrsGroups: [ "core", "presentation" ],
    attrs: [ "class", "style", "d", "horiz-adv-x", "vert-origin-x", "vert-origin-y", "vert-adv-y", "unicode", "glyph-name", "orientation", "arabic-form", "lang" ],
    defaults: {
      "arabic-form": "initial"
    },
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  glyphRef: {
    attrsGroups: [ "core", "presentation" ],
    attrs: [ "class", "style", "d", "horiz-adv-x", "vert-origin-x", "vert-origin-y", "vert-adv-y" ],
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  hatch: {
    attrsGroups: [ "core", "presentation", "xlink" ],
    attrs: [ "class", "style", "x", "y", "pitch", "rotate", "hatchUnits", "hatchContentUnits", "transform" ],
    defaults: {
      hatchUnits: "objectBoundingBox",
      hatchContentUnits: "userSpaceOnUse",
      x: "0",
      y: "0",
      pitch: "0",
      rotate: "0"
    },
    contentGroups: [ "animation", "descriptive" ],
    content: [ "hatchPath" ]
  },
  hatchPath: {
    attrsGroups: [ "core", "presentation", "xlink" ],
    attrs: [ "class", "style", "d", "offset" ],
    defaults: {
      offset: "0"
    },
    contentGroups: [ "animation", "descriptive" ]
  },
  hkern: {
    attrsGroups: [ "core" ],
    attrs: [ "u1", "g1", "u2", "g2", "k" ]
  },
  image: {
    attrsGroups: [ "core", "conditionalProcessing", "graphicalEvent", "xlink", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "preserveAspectRatio", "transform", "x", "y", "width", "height", "href", "xlink:href" ],
    defaults: {
      x: "0",
      y: "0",
      preserveAspectRatio: "xMidYMid meet"
    },
    contentGroups: [ "animation", "descriptive" ]
  },
  line: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "x1", "y1", "x2", "y2" ],
    defaults: {
      x1: "0",
      y1: "0",
      x2: "0",
      y2: "0"
    },
    contentGroups: [ "animation", "descriptive" ]
  },
  linearGradient: {
    attrsGroups: [ "core", "presentation", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "x1", "y1", "x2", "y2", "gradientUnits", "gradientTransform", "spreadMethod", "href", "xlink:href" ],
    defaults: {
      x1: "0",
      y1: "0",
      x2: "100%",
      y2: "0",
      spreadMethod: "pad"
    },
    contentGroups: [ "descriptive" ],
    content: [ "animate", "animateTransform", "set", "stop" ]
  },
  marker: {
    attrsGroups: [ "core", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "viewBox", "preserveAspectRatio", "refX", "refY", "markerUnits", "markerWidth", "markerHeight", "orient" ],
    defaults: {
      markerUnits: "strokeWidth",
      refX: "0",
      refY: "0",
      markerWidth: "3",
      markerHeight: "3"
    },
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  mask: {
    attrsGroups: [ "conditionalProcessing", "core", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "x", "y", "width", "height", "mask-type", "maskUnits", "maskContentUnits" ],
    defaults: {
      maskUnits: "objectBoundingBox",
      maskContentUnits: "userSpaceOnUse",
      x: "-10%",
      y: "-10%",
      width: "120%",
      height: "120%"
    },
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  metadata: {
    attrsGroups: [ "core" ]
  },
  "missing-glyph": {
    attrsGroups: [ "core", "presentation" ],
    attrs: [ "class", "style", "d", "horiz-adv-x", "vert-origin-x", "vert-origin-y", "vert-adv-y" ],
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  mpath: {
    attrsGroups: [ "core", "xlink" ],
    attrs: [ "externalResourcesRequired", "href", "xlink:href" ],
    contentGroups: [ "descriptive" ]
  },
  path: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "d", "pathLength" ],
    contentGroups: [ "animation", "descriptive" ]
  },
  pattern: {
    attrsGroups: [ "conditionalProcessing", "core", "presentation", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "viewBox", "preserveAspectRatio", "x", "y", "width", "height", "patternUnits", "patternContentUnits", "patternTransform", "href", "xlink:href" ],
    defaults: {
      patternUnits: "objectBoundingBox",
      patternContentUnits: "userSpaceOnUse",
      x: "0",
      y: "0",
      width: "0",
      height: "0",
      preserveAspectRatio: "xMidYMid meet"
    },
    contentGroups: [ "animation", "descriptive", "paintServer", "shape", "structural" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  polygon: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "points" ],
    contentGroups: [ "animation", "descriptive" ]
  },
  polyline: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "points" ],
    contentGroups: [ "animation", "descriptive" ]
  },
  radialGradient: {
    attrsGroups: [ "core", "presentation", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "cx", "cy", "r", "fx", "fy", "fr", "gradientUnits", "gradientTransform", "spreadMethod", "href", "xlink:href" ],
    defaults: {
      gradientUnits: "objectBoundingBox",
      cx: "50%",
      cy: "50%",
      r: "50%"
    },
    contentGroups: [ "descriptive" ],
    content: [ "animate", "animateTransform", "set", "stop" ]
  },
  meshGradient: {
    attrsGroups: [ "core", "presentation", "xlink" ],
    attrs: [ "class", "style", "x", "y", "gradientUnits", "transform" ],
    contentGroups: [ "descriptive", "paintServer", "animation" ],
    content: [ "meshRow" ]
  },
  meshRow: {
    attrsGroups: [ "core", "presentation" ],
    attrs: [ "class", "style" ],
    contentGroups: [ "descriptive" ],
    content: [ "meshPatch" ]
  },
  meshPatch: {
    attrsGroups: [ "core", "presentation" ],
    attrs: [ "class", "style" ],
    contentGroups: [ "descriptive" ],
    content: [ "stop" ]
  },
  rect: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "x", "y", "width", "height", "rx", "ry" ],
    defaults: {
      x: "0",
      y: "0"
    },
    contentGroups: [ "animation", "descriptive" ]
  },
  script: {
    attrsGroups: [ "core", "xlink" ],
    attrs: [ "externalResourcesRequired", "type", "href", "xlink:href" ]
  },
  set: {
    attrsGroups: [ "conditionalProcessing", "core", "animation", "xlink", "animationAttributeTarget", "animationTiming" ],
    attrs: [ "externalResourcesRequired", "to" ],
    contentGroups: [ "descriptive" ]
  },
  solidColor: {
    attrsGroups: [ "core", "presentation" ],
    attrs: [ "class", "style" ],
    contentGroups: [ "paintServer" ]
  },
  stop: {
    attrsGroups: [ "core", "presentation" ],
    attrs: [ "class", "style", "offset", "path" ],
    content: [ "animate", "animateColor", "set" ]
  },
  style: {
    attrsGroups: [ "core" ],
    attrs: [ "type", "media", "title" ],
    defaults: {
      type: "text/css"
    }
  },
  svg: {
    attrsGroups: [ "conditionalProcessing", "core", "documentEvent", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "x", "y", "width", "height", "viewBox", "preserveAspectRatio", "zoomAndPan", "version", "baseProfile", "contentScriptType", "contentStyleType" ],
    defaults: {
      x: "0",
      y: "0",
      width: "100%",
      height: "100%",
      preserveAspectRatio: "xMidYMid meet",
      zoomAndPan: "magnify",
      version: "1.1",
      baseProfile: "none",
      contentScriptType: "application/ecmascript",
      contentStyleType: "text/css"
    },
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  switch: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform" ],
    contentGroups: [ "animation", "descriptive", "shape" ],
    content: [ "a", "foreignObject", "g", "image", "svg", "switch", "text", "use" ]
  },
  symbol: {
    attrsGroups: [ "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "preserveAspectRatio", "viewBox", "refX", "refY" ],
    defaults: {
      refX: "0",
      refY: "0"
    },
    contentGroups: [ "animation", "descriptive", "shape", "structural", "paintServer" ],
    content: [ "a", "altGlyphDef", "clipPath", "color-profile", "cursor", "filter", "font", "font-face", "foreignObject", "image", "marker", "mask", "pattern", "script", "style", "switch", "text", "view" ]
  },
  text: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "lengthAdjust", "x", "y", "dx", "dy", "rotate", "textLength" ],
    defaults: {
      x: "0",
      y: "0",
      lengthAdjust: "spacing"
    },
    contentGroups: [ "animation", "descriptive", "textContentChild" ],
    content: [ "a" ]
  },
  textPath: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "href", "xlink:href", "startOffset", "method", "spacing", "d" ],
    defaults: {
      startOffset: "0",
      method: "align",
      spacing: "exact"
    },
    contentGroups: [ "descriptive" ],
    content: [ "a", "altGlyph", "animate", "animateColor", "set", "tref", "tspan" ]
  },
  title: {
    attrsGroups: [ "core" ],
    attrs: [ "class", "style" ]
  },
  tref: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "href", "xlink:href" ],
    contentGroups: [ "descriptive" ],
    content: [ "animate", "animateColor", "set" ]
  },
  tspan: {
    attrsGroups: [ "conditionalProcessing", "core", "graphicalEvent", "presentation" ],
    attrs: [ "class", "style", "externalResourcesRequired", "x", "y", "dx", "dy", "rotate", "textLength", "lengthAdjust" ],
    contentGroups: [ "descriptive" ],
    content: [ "a", "altGlyph", "animate", "animateColor", "set", "tref", "tspan" ]
  },
  use: {
    attrsGroups: [ "core", "conditionalProcessing", "graphicalEvent", "presentation", "xlink" ],
    attrs: [ "class", "style", "externalResourcesRequired", "transform", "x", "y", "width", "height", "href", "xlink:href" ],
    defaults: {
      x: "0",
      y: "0"
    },
    contentGroups: [ "animation", "descriptive" ]
  },
  view: {
    attrsGroups: [ "core" ],
    attrs: [ "externalResourcesRequired", "viewBox", "preserveAspectRatio", "zoomAndPan", "viewTarget" ],
    contentGroups: [ "descriptive" ]
  },
  vkern: {
    attrsGroups: [ "core" ],
    attrs: [ "u1", "g1", "u2", "g2", "k" ]
  }
}, exports.editorNamespaces = [ "http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd", "http://inkscape.sourceforge.net/DTD/sodipodi-0.dtd", "http://www.inkscape.org/namespaces/inkscape", "http://www.bohemiancoding.com/sketch/ns", "http://ns.adobe.com/AdobeIllustrator/10.0/", "http://ns.adobe.com/Graphs/1.0/", "http://ns.adobe.com/AdobeSVGViewerExtensions/3.0/", "http://ns.adobe.com/Variables/1.0/", "http://ns.adobe.com/SaveForWeb/1.0/", "http://ns.adobe.com/Extensibility/1.0/", "http://ns.adobe.com/Flows/1.0/", "http://ns.adobe.com/ImageReplacement/1.0/", "http://ns.adobe.com/GenericCustomNamespace/1.0/", "http://ns.adobe.com/XPath/1.0/", "http://schemas.microsoft.com/visio/2003/SVGExtensions/", "http://taptrix.com/vectorillustrator/svg_extensions", "http://www.figma.com/figma/ns", "http://purl.org/dc/elements/1.1/", "http://creativecommons.org/ns#", "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "http://www.serif.com/", "http://www.vector.evaxdesign.sk" ], 
exports.referencesProps = [ "clip-path", "color-profile", "fill", "filter", "marker-start", "marker-mid", "marker-end", "mask", "stroke", "style" ], 
exports.inheritableAttrs = [ "clip-rule", "color", "color-interpolation", "color-interpolation-filters", "color-profile", "color-rendering", "cursor", "direction", "dominant-baseline", "fill", "fill-opacity", "fill-rule", "font", "font-family", "font-size", "font-size-adjust", "font-stretch", "font-style", "font-variant", "font-weight", "glyph-orientation-horizontal", "glyph-orientation-vertical", "image-rendering", "letter-spacing", "marker", "marker-end", "marker-mid", "marker-start", "paint-order", "pointer-events", "shape-rendering", "stroke", "stroke-dasharray", "stroke-dashoffset", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-opacity", "stroke-width", "text-anchor", "text-rendering", "transform", "visibility", "word-spacing", "writing-mode" ], 
exports.presentationNonInheritableGroupAttrs = [ "display", "clip-path", "filter", "mask", "opacity", "text-decoration", "transform", "unicode-bidi" ], 
exports.colorsNames = {
  aliceblue: "#f0f8ff",
  antiquewhite: "#faebd7",
  aqua: "#0ff",
  aquamarine: "#7fffd4",
  azure: "#f0ffff",
  beige: "#f5f5dc",
  bisque: "#ffe4c4",
  black: "#000",
  blanchedalmond: "#ffebcd",
  blue: "#00f",
  blueviolet: "#8a2be2",
  brown: "#a52a2a",
  burlywood: "#deb887",
  cadetblue: "#5f9ea0",
  chartreuse: "#7fff00",
  chocolate: "#d2691e",
  coral: "#ff7f50",
  cornflowerblue: "#6495ed",
  cornsilk: "#fff8dc",
  crimson: "#dc143c",
  cyan: "#0ff",
  darkblue: "#00008b",
  darkcyan: "#008b8b",
  darkgoldenrod: "#b8860b",
  darkgray: "#a9a9a9",
  darkgreen: "#006400",
  darkgrey: "#a9a9a9",
  darkkhaki: "#bdb76b",
  darkmagenta: "#8b008b",
  darkolivegreen: "#556b2f",
  darkorange: "#ff8c00",
  darkorchid: "#9932cc",
  darkred: "#8b0000",
  darksalmon: "#e9967a",
  darkseagreen: "#8fbc8f",
  darkslateblue: "#483d8b",
  darkslategray: "#2f4f4f",
  darkslategrey: "#2f4f4f",
  darkturquoise: "#00ced1",
  darkviolet: "#9400d3",
  deeppink: "#ff1493",
  deepskyblue: "#00bfff",
  dimgray: "#696969",
  dimgrey: "#696969",
  dodgerblue: "#1e90ff",
  firebrick: "#b22222",
  floralwhite: "#fffaf0",
  forestgreen: "#228b22",
  fuchsia: "#f0f",
  gainsboro: "#dcdcdc",
  ghostwhite: "#f8f8ff",
  gold: "#ffd700",
  goldenrod: "#daa520",
  gray: "#808080",
  green: "#008000",
  greenyellow: "#adff2f",
  grey: "#808080",
  honeydew: "#f0fff0",
  hotpink: "#ff69b4",
  indianred: "#cd5c5c",
  indigo: "#4b0082",
  ivory: "#fffff0",
  khaki: "#f0e68c",
  lavender: "#e6e6fa",
  lavenderblush: "#fff0f5",
  lawngreen: "#7cfc00",
  lemonchiffon: "#fffacd",
  lightblue: "#add8e6",
  lightcoral: "#f08080",
  lightcyan: "#e0ffff",
  lightgoldenrodyellow: "#fafad2",
  lightgray: "#d3d3d3",
  lightgreen: "#90ee90",
  lightgrey: "#d3d3d3",
  lightpink: "#ffb6c1",
  lightsalmon: "#ffa07a",
  lightseagreen: "#20b2aa",
  lightskyblue: "#87cefa",
  lightslategray: "#789",
  lightslategrey: "#789",
  lightsteelblue: "#b0c4de",
  lightyellow: "#ffffe0",
  lime: "#0f0",
  limegreen: "#32cd32",
  linen: "#faf0e6",
  magenta: "#f0f",
  maroon: "#800000",
  mediumaquamarine: "#66cdaa",
  mediumblue: "#0000cd",
  mediumorchid: "#ba55d3",
  mediumpurple: "#9370db",
  mediumseagreen: "#3cb371",
  mediumslateblue: "#7b68ee",
  mediumspringgreen: "#00fa9a",
  mediumturquoise: "#48d1cc",
  mediumvioletred: "#c71585",
  midnightblue: "#191970",
  mintcream: "#f5fffa",
  mistyrose: "#ffe4e1",
  moccasin: "#ffe4b5",
  navajowhite: "#ffdead",
  navy: "#000080",
  oldlace: "#fdf5e6",
  olive: "#808000",
  olivedrab: "#6b8e23",
  orange: "#ffa500",
  orangered: "#ff4500",
  orchid: "#da70d6",
  palegoldenrod: "#eee8aa",
  palegreen: "#98fb98",
  paleturquoise: "#afeeee",
  palevioletred: "#db7093",
  papayawhip: "#ffefd5",
  peachpuff: "#ffdab9",
  peru: "#cd853f",
  pink: "#ffc0cb",
  plum: "#dda0dd",
  powderblue: "#b0e0e6",
  purple: "#800080",
  rebeccapurple: "#639",
  red: "#f00",
  rosybrown: "#bc8f8f",
  royalblue: "#4169e1",
  saddlebrown: "#8b4513",
  salmon: "#fa8072",
  sandybrown: "#f4a460",
  seagreen: "#2e8b57",
  seashell: "#fff5ee",
  sienna: "#a0522d",
  silver: "#c0c0c0",
  skyblue: "#87ceeb",
  slateblue: "#6a5acd",
  slategray: "#708090",
  slategrey: "#708090",
  snow: "#fffafa",
  springgreen: "#00ff7f",
  steelblue: "#4682b4",
  tan: "#d2b48c",
  teal: "#008080",
  thistle: "#d8bfd8",
  tomato: "#ff6347",
  turquoise: "#40e0d0",
  violet: "#ee82ee",
  wheat: "#f5deb3",
  white: "#fff",
  whitesmoke: "#f5f5f5",
  yellow: "#ff0",
  yellowgreen: "#9acd32"
}, exports.colorsShortNames = {
  "#f0ffff": "azure",
  "#f5f5dc": "beige",
  "#ffe4c4": "bisque",
  "#a52a2a": "brown",
  "#ff7f50": "coral",
  "#ffd700": "gold",
  "#808080": "gray",
  "#008000": "green",
  "#4b0082": "indigo",
  "#fffff0": "ivory",
  "#f0e68c": "khaki",
  "#faf0e6": "linen",
  "#800000": "maroon",
  "#000080": "navy",
  "#808000": "olive",
  "#ffa500": "orange",
  "#da70d6": "orchid",
  "#cd853f": "peru",
  "#ffc0cb": "pink",
  "#dda0dd": "plum",
  "#800080": "purple",
  "#f00": "red",
  "#ff0000": "red",
  "#fa8072": "salmon",
  "#a0522d": "sienna",
  "#c0c0c0": "silver",
  "#fffafa": "snow",
  "#d2b48c": "tan",
  "#008080": "teal",
  "#ff6347": "tomato",
  "#ee82ee": "violet",
  "#f5deb3": "wheat"
}, exports.colorsProps = [ "color", "fill", "stroke", "stop-color", "flood-color", "lighting-color" ];

const SAX = sax, {textElems: textElems$1} = _collections;

class SvgoParserError extends Error {
  constructor(message, line, column, source, file) {
    super(message), this.name = "SvgoParserError", this.message = `${file || "<input>"}:${line}:${column}: ${message}`, 
    this.reason = message, this.line = line, this.column = column, this.source = source, 
    Error.captureStackTrace && Error.captureStackTrace(this, SvgoParserError);
  }
  toString() {
    const lines = this.source.split(/\r?\n/), startLine = Math.max(this.line - 3, 0), endLine = Math.min(this.line + 2, lines.length), lineNumberWidth = String(endLine).length, startColumn = Math.max(this.column - 54, 0), endColumn = Math.max(this.column + 20, 80), code = lines.slice(startLine, endLine).map(((line, index) => {
      const lineSlice = line.slice(startColumn, endColumn);
      let ellipsisPrefix = "", ellipsisSuffix = "";
      0 !== startColumn && (ellipsisPrefix = startColumn > line.length - 1 ? " " : "…"), 
      endColumn < line.length - 1 && (ellipsisSuffix = "…");
      const number = startLine + 1 + index, gutter = ` ${number.toString().padStart(lineNumberWidth)} | `;
      if (number === this.line) {
        const gutterSpacing = gutter.replace(/[^|]/g, " ");
        return `>${gutter}${ellipsisPrefix}${lineSlice}${ellipsisSuffix}\n ${gutterSpacing + (ellipsisPrefix + line.slice(startColumn, this.column - 1)).replace(/[^\t]/g, " ")}^`;
      }
      return ` ${gutter}${ellipsisPrefix}${lineSlice}${ellipsisSuffix}`;
    })).join("\n");
    return `${this.name}: ${this.message}\n\n${code}\n`;
  }
}

const entityDeclaration = /<!ENTITY\s+(\S+)\s+(?:'([^']+)'|"([^"]+)")\s*>/g, config$4 = {
  strict: !0,
  trim: !1,
  normalize: !1,
  lowercase: !0,
  xmlns: !0,
  position: !0
};

parser$4.parseSvg = (data, from) => {
  const sax = SAX.parser(config$4.strict, config$4), root = {
    type: "root",
    children: []
  };
  let current = root;
  const stack = [ root ], pushToContent = node => {
    Object.defineProperty(node, "parentNode", {
      writable: !0,
      value: current
    }), current.children.push(node);
  };
  return sax.ondoctype = doctype => {
    pushToContent({
      type: "doctype",
      name: "svg",
      data: {
        doctype
      }
    });
    const subsetStart = doctype.indexOf("[");
    if (subsetStart >= 0) {
      entityDeclaration.lastIndex = subsetStart;
      let entityMatch = entityDeclaration.exec(data);
      for (;null != entityMatch; ) sax.ENTITIES[entityMatch[1]] = entityMatch[2] || entityMatch[3], 
      entityMatch = entityDeclaration.exec(data);
    }
  }, sax.onprocessinginstruction = data => {
    const node = {
      type: "instruction",
      name: data.name,
      value: data.body
    };
    pushToContent(node);
  }, sax.oncomment = comment => {
    const node = {
      type: "comment",
      value: comment.trim()
    };
    pushToContent(node);
  }, sax.oncdata = cdata => {
    pushToContent({
      type: "cdata",
      value: cdata
    });
  }, sax.onopentag = data => {
    let element = {
      type: "element",
      name: data.name,
      attributes: {},
      children: []
    };
    for (const [name, attr] of Object.entries(data.attributes)) element.attributes[name] = attr.value;
    pushToContent(element), current = element, stack.push(element);
  }, sax.ontext = text => {
    if ("element" === current.type) if (textElems$1.includes(current.name)) {
      pushToContent({
        type: "text",
        value: text
      });
    } else if (/\S/.test(text)) {
      const node = {
        type: "text",
        value: text.trim()
      };
      pushToContent(node);
    }
  }, sax.onclosetag = () => {
    stack.pop(), current = stack[stack.length - 1];
  }, sax.onerror = e => {
    const error = new SvgoParserError(e.reason, e.line + 1, e.column, data, from);
    if (-1 === e.message.indexOf("Unexpected end")) throw error;
  }, sax.write(data).close(), root;
};

var stringifier = {};

const {textElems} = _collections, defaults = {
  doctypeStart: "<!DOCTYPE",
  doctypeEnd: ">",
  procInstStart: "<?",
  procInstEnd: "?>",
  tagOpenStart: "<",
  tagOpenEnd: ">",
  tagCloseStart: "</",
  tagCloseEnd: ">",
  tagShortStart: "<",
  tagShortEnd: "/>",
  attrStart: '="',
  attrEnd: '"',
  commentStart: "\x3c!--",
  commentEnd: "--\x3e",
  cdataStart: "<![CDATA[",
  cdataEnd: "]]>",
  textStart: "",
  textEnd: "",
  indent: 4,
  regEntities: /[&'"<>]/g,
  regValEntities: /[&"<>]/g,
  encodeEntity: c => entities[c],
  pretty: !1,
  useShortTags: !0,
  eol: "lf",
  finalNewline: !1
}, entities = {
  "&": "&amp;",
  "'": "&apos;",
  '"': "&quot;",
  ">": "&gt;",
  "<": "&lt;"
};

stringifier.stringifySvg = (data, userOptions = {}) => {
  const config = {
    ...defaults,
    ...userOptions
  }, indent = config.indent;
  let newIndent = "    ";
  "number" == typeof indent && !1 === Number.isNaN(indent) ? newIndent = indent < 0 ? "\t" : " ".repeat(indent) : "string" == typeof indent && (newIndent = indent);
  const state = {
    indent: newIndent,
    textContext: null,
    indentLevel: 0
  }, eol = "crlf" === config.eol ? "\r\n" : "\n";
  config.pretty && (config.doctypeEnd += eol, config.procInstEnd += eol, config.commentEnd += eol, 
  config.cdataEnd += eol, config.tagShortEnd += eol, config.tagOpenEnd += eol, config.tagCloseEnd += eol, 
  config.textEnd += eol);
  let svg = stringifyNode(data, config, state);
  return config.finalNewline && svg.length > 0 && "\n" !== svg[svg.length - 1] && (svg += eol), 
  svg;
};

const stringifyNode = (data, config, state) => {
  let svg = "";
  state.indentLevel += 1;
  for (const item of data.children) "element" === item.type && (svg += stringifyElement(item, config, state)), 
  "text" === item.type && (svg += stringifyText(item, config, state)), "doctype" === item.type && (svg += stringifyDoctype(item, config)), 
  "instruction" === item.type && (svg += stringifyInstruction(item, config)), "comment" === item.type && (svg += stringifyComment(item, config)), 
  "cdata" === item.type && (svg += stringifyCdata(item, config, state));
  return state.indentLevel -= 1, svg;
}, createIndent = (config, state) => {
  let indent = "";
  return config.pretty && null == state.textContext && (indent = state.indent.repeat(state.indentLevel - 1)), 
  indent;
}, stringifyDoctype = (node, config) => config.doctypeStart + node.data.doctype + config.doctypeEnd, stringifyInstruction = (node, config) => config.procInstStart + node.name + " " + node.value + config.procInstEnd, stringifyComment = (node, config) => config.commentStart + node.value + config.commentEnd, stringifyCdata = (node, config, state) => createIndent(config, state) + config.cdataStart + node.value + config.cdataEnd, stringifyElement = (node, config, state) => {
  if (0 === node.children.length) return config.useShortTags ? createIndent(config, state) + config.tagShortStart + node.name + stringifyAttributes(node, config) + config.tagShortEnd : createIndent(config, state) + config.tagShortStart + node.name + stringifyAttributes(node, config) + config.tagOpenEnd + config.tagCloseStart + node.name + config.tagCloseEnd;
  {
    let tagOpenStart = config.tagOpenStart, tagOpenEnd = config.tagOpenEnd, tagCloseStart = config.tagCloseStart, tagCloseEnd = config.tagCloseEnd, openIndent = createIndent(config, state), closeIndent = createIndent(config, state);
    state.textContext ? (tagOpenStart = defaults.tagOpenStart, tagOpenEnd = defaults.tagOpenEnd, 
    tagCloseStart = defaults.tagCloseStart, tagCloseEnd = defaults.tagCloseEnd, openIndent = "") : textElems.includes(node.name) && (tagOpenEnd = defaults.tagOpenEnd, 
    tagCloseStart = defaults.tagCloseStart, closeIndent = "", state.textContext = node);
    const children = stringifyNode(node, config, state);
    return state.textContext === node && (state.textContext = null), openIndent + tagOpenStart + node.name + stringifyAttributes(node, config) + tagOpenEnd + children + closeIndent + tagCloseStart + node.name + tagCloseEnd;
  }
}, stringifyAttributes = (node, config) => {
  let attrs = "";
  for (const [name, value] of Object.entries(node.attributes)) if (void 0 !== value) {
    const encodedValue = value.toString().replace(config.regValEntities, config.encodeEntity);
    attrs += " " + name + config.attrStart + encodedValue + config.attrEnd;
  } else attrs += " " + name;
  return attrs;
}, stringifyText = (node, config, state) => createIndent(config, state) + config.textStart + node.value.replace(config.regEntities, config.encodeEntity) + (state.textContext ? "" : config.textEnd);

var plugins = {}, builtin$1 = {}, tools = {}, xast = {}, lib$6 = {}, lib$5 = {}, stringify$1 = {}, lib$4 = {}, lib$3 = {};

!function(exports) {
  var ElementType;
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.Doctype = exports.CDATA = exports.Tag = exports.Style = exports.Script = exports.Comment = exports.Directive = exports.Text = exports.Root = exports.isTag = exports.ElementType = void 0, 
  function(ElementType) {
    ElementType.Root = "root", ElementType.Text = "text", ElementType.Directive = "directive", 
    ElementType.Comment = "comment", ElementType.Script = "script", ElementType.Style = "style", 
    ElementType.Tag = "tag", ElementType.CDATA = "cdata", ElementType.Doctype = "doctype";
  }(ElementType = exports.ElementType || (exports.ElementType = {})), exports.isTag = function(elem) {
    return elem.type === ElementType.Tag || elem.type === ElementType.Script || elem.type === ElementType.Style;
  }, exports.Root = ElementType.Root, exports.Text = ElementType.Text, exports.Directive = ElementType.Directive, 
  exports.Comment = ElementType.Comment, exports.Script = ElementType.Script, exports.Style = ElementType.Style, 
  exports.Tag = ElementType.Tag, exports.CDATA = ElementType.CDATA, exports.Doctype = ElementType.Doctype;
}(lib$3);

var extendStatics, node$2 = {}, __extends = commonjsGlobal && commonjsGlobal.__extends || (extendStatics = function(d, b) {
  return extendStatics = Object.setPrototypeOf || {
    __proto__: []
  } instanceof Array && function(d, b) {
    d.__proto__ = b;
  } || function(d, b) {
    for (var p in b) Object.prototype.hasOwnProperty.call(b, p) && (d[p] = b[p]);
  }, extendStatics(d, b);
}, function(d, b) {
  if ("function" != typeof b && null !== b) throw new TypeError("Class extends value " + String(b) + " is not a constructor or null");
  function __() {
    this.constructor = d;
  }
  extendStatics(d, b), d.prototype = null === b ? Object.create(b) : (__.prototype = b.prototype, 
  new __);
}), __assign$1 = commonjsGlobal && commonjsGlobal.__assign || function() {
  return __assign$1 = Object.assign || function(t) {
    for (var s, i = 1, n = arguments.length; i < n; i++) for (var p in s = arguments[i]) Object.prototype.hasOwnProperty.call(s, p) && (t[p] = s[p]);
    return t;
  }, __assign$1.apply(this, arguments);
};

Object.defineProperty(node$2, "__esModule", {
  value: !0
}), node$2.cloneNode = node$2.hasChildren = node$2.isDocument = node$2.isDirective = node$2.isComment = node$2.isText = node$2.isCDATA = node$2.isTag = node$2.Element = node$2.Document = node$2.CDATA = node$2.NodeWithChildren = node$2.ProcessingInstruction = node$2.Comment = node$2.Text = node$2.DataNode = node$2.Node = void 0;

var domelementtype_1$1 = lib$3, Node = function() {
  function Node() {
    this.parent = null, this.prev = null, this.next = null, this.startIndex = null, 
    this.endIndex = null;
  }
  return Object.defineProperty(Node.prototype, "parentNode", {
    get: function() {
      return this.parent;
    },
    set: function(parent) {
      this.parent = parent;
    },
    enumerable: !1,
    configurable: !0
  }), Object.defineProperty(Node.prototype, "previousSibling", {
    get: function() {
      return this.prev;
    },
    set: function(prev) {
      this.prev = prev;
    },
    enumerable: !1,
    configurable: !0
  }), Object.defineProperty(Node.prototype, "nextSibling", {
    get: function() {
      return this.next;
    },
    set: function(next) {
      this.next = next;
    },
    enumerable: !1,
    configurable: !0
  }), Node.prototype.cloneNode = function(recursive) {
    return void 0 === recursive && (recursive = !1), cloneNode(this, recursive);
  }, Node;
}();

node$2.Node = Node;

var DataNode = function(_super) {
  function DataNode(data) {
    var _this = _super.call(this) || this;
    return _this.data = data, _this;
  }
  return __extends(DataNode, _super), Object.defineProperty(DataNode.prototype, "nodeValue", {
    get: function() {
      return this.data;
    },
    set: function(data) {
      this.data = data;
    },
    enumerable: !1,
    configurable: !0
  }), DataNode;
}(Node);

node$2.DataNode = DataNode;

var Text = function(_super) {
  function Text() {
    var _this = null !== _super && _super.apply(this, arguments) || this;
    return _this.type = domelementtype_1$1.ElementType.Text, _this;
  }
  return __extends(Text, _super), Object.defineProperty(Text.prototype, "nodeType", {
    get: function() {
      return 3;
    },
    enumerable: !1,
    configurable: !0
  }), Text;
}(DataNode);

node$2.Text = Text;

var Comment$a = function(_super) {
  function Comment() {
    var _this = null !== _super && _super.apply(this, arguments) || this;
    return _this.type = domelementtype_1$1.ElementType.Comment, _this;
  }
  return __extends(Comment, _super), Object.defineProperty(Comment.prototype, "nodeType", {
    get: function() {
      return 8;
    },
    enumerable: !1,
    configurable: !0
  }), Comment;
}(DataNode);

node$2.Comment = Comment$a;

var ProcessingInstruction = function(_super) {
  function ProcessingInstruction(name, data) {
    var _this = _super.call(this, data) || this;
    return _this.name = name, _this.type = domelementtype_1$1.ElementType.Directive, 
    _this;
  }
  return __extends(ProcessingInstruction, _super), Object.defineProperty(ProcessingInstruction.prototype, "nodeType", {
    get: function() {
      return 1;
    },
    enumerable: !1,
    configurable: !0
  }), ProcessingInstruction;
}(DataNode);

node$2.ProcessingInstruction = ProcessingInstruction;

var NodeWithChildren = function(_super) {
  function NodeWithChildren(children) {
    var _this = _super.call(this) || this;
    return _this.children = children, _this;
  }
  return __extends(NodeWithChildren, _super), Object.defineProperty(NodeWithChildren.prototype, "firstChild", {
    get: function() {
      var _a;
      return null !== (_a = this.children[0]) && void 0 !== _a ? _a : null;
    },
    enumerable: !1,
    configurable: !0
  }), Object.defineProperty(NodeWithChildren.prototype, "lastChild", {
    get: function() {
      return this.children.length > 0 ? this.children[this.children.length - 1] : null;
    },
    enumerable: !1,
    configurable: !0
  }), Object.defineProperty(NodeWithChildren.prototype, "childNodes", {
    get: function() {
      return this.children;
    },
    set: function(children) {
      this.children = children;
    },
    enumerable: !1,
    configurable: !0
  }), NodeWithChildren;
}(Node);

node$2.NodeWithChildren = NodeWithChildren;

var CDATA = function(_super) {
  function CDATA() {
    var _this = null !== _super && _super.apply(this, arguments) || this;
    return _this.type = domelementtype_1$1.ElementType.CDATA, _this;
  }
  return __extends(CDATA, _super), Object.defineProperty(CDATA.prototype, "nodeType", {
    get: function() {
      return 4;
    },
    enumerable: !1,
    configurable: !0
  }), CDATA;
}(NodeWithChildren);

node$2.CDATA = CDATA;

var Document = function(_super) {
  function Document() {
    var _this = null !== _super && _super.apply(this, arguments) || this;
    return _this.type = domelementtype_1$1.ElementType.Root, _this;
  }
  return __extends(Document, _super), Object.defineProperty(Document.prototype, "nodeType", {
    get: function() {
      return 9;
    },
    enumerable: !1,
    configurable: !0
  }), Document;
}(NodeWithChildren);

node$2.Document = Document;

var Element = function(_super) {
  function Element(name, attribs, children, type) {
    void 0 === children && (children = []), void 0 === type && (type = "script" === name ? domelementtype_1$1.ElementType.Script : "style" === name ? domelementtype_1$1.ElementType.Style : domelementtype_1$1.ElementType.Tag);
    var _this = _super.call(this, children) || this;
    return _this.name = name, _this.attribs = attribs, _this.type = type, _this;
  }
  return __extends(Element, _super), Object.defineProperty(Element.prototype, "nodeType", {
    get: function() {
      return 1;
    },
    enumerable: !1,
    configurable: !0
  }), Object.defineProperty(Element.prototype, "tagName", {
    get: function() {
      return this.name;
    },
    set: function(name) {
      this.name = name;
    },
    enumerable: !1,
    configurable: !0
  }), Object.defineProperty(Element.prototype, "attributes", {
    get: function() {
      var _this = this;
      return Object.keys(this.attribs).map((function(name) {
        var _a, _b;
        return {
          name,
          value: _this.attribs[name],
          namespace: null === (_a = _this["x-attribsNamespace"]) || void 0 === _a ? void 0 : _a[name],
          prefix: null === (_b = _this["x-attribsPrefix"]) || void 0 === _b ? void 0 : _b[name]
        };
      }));
    },
    enumerable: !1,
    configurable: !0
  }), Element;
}(NodeWithChildren);

function isTag$1(node) {
  return (0, domelementtype_1$1.isTag)(node);
}

function isCDATA(node) {
  return node.type === domelementtype_1$1.ElementType.CDATA;
}

function isText(node) {
  return node.type === domelementtype_1$1.ElementType.Text;
}

function isComment(node) {
  return node.type === domelementtype_1$1.ElementType.Comment;
}

function isDirective(node) {
  return node.type === domelementtype_1$1.ElementType.Directive;
}

function isDocument(node) {
  return node.type === domelementtype_1$1.ElementType.Root;
}

function cloneNode(node, recursive) {
  var result;
  if (void 0 === recursive && (recursive = !1), isText(node)) result = new Text(node.data); else if (isComment(node)) result = new Comment$a(node.data); else if (isTag$1(node)) {
    var children = recursive ? cloneChildren(node.children) : [], clone_1 = new Element(node.name, __assign$1({}, node.attribs), children);
    children.forEach((function(child) {
      return child.parent = clone_1;
    })), null != node.namespace && (clone_1.namespace = node.namespace), node["x-attribsNamespace"] && (clone_1["x-attribsNamespace"] = __assign$1({}, node["x-attribsNamespace"])), 
    node["x-attribsPrefix"] && (clone_1["x-attribsPrefix"] = __assign$1({}, node["x-attribsPrefix"])), 
    result = clone_1;
  } else if (isCDATA(node)) {
    children = recursive ? cloneChildren(node.children) : [];
    var clone_2 = new CDATA(children);
    children.forEach((function(child) {
      return child.parent = clone_2;
    })), result = clone_2;
  } else if (isDocument(node)) {
    children = recursive ? cloneChildren(node.children) : [];
    var clone_3 = new Document(children);
    children.forEach((function(child) {
      return child.parent = clone_3;
    })), node["x-mode"] && (clone_3["x-mode"] = node["x-mode"]), result = clone_3;
  } else {
    if (!isDirective(node)) throw new Error("Not implemented yet: ".concat(node.type));
    var instruction = new ProcessingInstruction(node.name, node.data);
    null != node["x-name"] && (instruction["x-name"] = node["x-name"], instruction["x-publicId"] = node["x-publicId"], 
    instruction["x-systemId"] = node["x-systemId"]), result = instruction;
  }
  return result.startIndex = node.startIndex, result.endIndex = node.endIndex, null != node.sourceCodeLocation && (result.sourceCodeLocation = node.sourceCodeLocation), 
  result;
}

function cloneChildren(childs) {
  for (var children = childs.map((function(child) {
    return cloneNode(child, !0);
  })), i = 1; i < children.length; i++) children[i].prev = children[i - 1], children[i - 1].next = children[i];
  return children;
}

node$2.Element = Element, node$2.isTag = isTag$1, node$2.isCDATA = isCDATA, node$2.isText = isText, 
node$2.isComment = isComment, node$2.isDirective = isDirective, node$2.isDocument = isDocument, 
node$2.hasChildren = function(node) {
  return Object.prototype.hasOwnProperty.call(node, "children");
}, node$2.cloneNode = cloneNode, function(exports) {
  var __createBinding = commonjsGlobal && commonjsGlobal.__createBinding || (Object.create ? function(o, m, k, k2) {
    void 0 === k2 && (k2 = k);
    var desc = Object.getOwnPropertyDescriptor(m, k);
    desc && !("get" in desc ? !m.__esModule : desc.writable || desc.configurable) || (desc = {
      enumerable: !0,
      get: function() {
        return m[k];
      }
    }), Object.defineProperty(o, k2, desc);
  } : function(o, m, k, k2) {
    void 0 === k2 && (k2 = k), o[k2] = m[k];
  }), __exportStar = commonjsGlobal && commonjsGlobal.__exportStar || function(m, exports) {
    for (var p in m) "default" === p || Object.prototype.hasOwnProperty.call(exports, p) || __createBinding(exports, m, p);
  };
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.DomHandler = void 0;
  var domelementtype_1 = lib$3, node_js_1 = node$2;
  __exportStar(node$2, exports);
  var defaultOpts = {
    withStartIndices: !1,
    withEndIndices: !1,
    xmlMode: !1
  }, DomHandler = function() {
    function DomHandler(callback, options, elementCB) {
      this.dom = [], this.root = new node_js_1.Document(this.dom), this.done = !1, this.tagStack = [ this.root ], 
      this.lastNode = null, this.parser = null, "function" == typeof options && (elementCB = options, 
      options = defaultOpts), "object" == typeof callback && (options = callback, callback = void 0), 
      this.callback = null != callback ? callback : null, this.options = null != options ? options : defaultOpts, 
      this.elementCB = null != elementCB ? elementCB : null;
    }
    return DomHandler.prototype.onparserinit = function(parser) {
      this.parser = parser;
    }, DomHandler.prototype.onreset = function() {
      this.dom = [], this.root = new node_js_1.Document(this.dom), this.done = !1, this.tagStack = [ this.root ], 
      this.lastNode = null, this.parser = null;
    }, DomHandler.prototype.onend = function() {
      this.done || (this.done = !0, this.parser = null, this.handleCallback(null));
    }, DomHandler.prototype.onerror = function(error) {
      this.handleCallback(error);
    }, DomHandler.prototype.onclosetag = function() {
      this.lastNode = null;
      var elem = this.tagStack.pop();
      this.options.withEndIndices && (elem.endIndex = this.parser.endIndex), this.elementCB && this.elementCB(elem);
    }, DomHandler.prototype.onopentag = function(name, attribs) {
      var type = this.options.xmlMode ? domelementtype_1.ElementType.Tag : void 0, element = new node_js_1.Element(name, attribs, void 0, type);
      this.addNode(element), this.tagStack.push(element);
    }, DomHandler.prototype.ontext = function(data) {
      var lastNode = this.lastNode;
      if (lastNode && lastNode.type === domelementtype_1.ElementType.Text) lastNode.data += data, 
      this.options.withEndIndices && (lastNode.endIndex = this.parser.endIndex); else {
        var node = new node_js_1.Text(data);
        this.addNode(node), this.lastNode = node;
      }
    }, DomHandler.prototype.oncomment = function(data) {
      if (this.lastNode && this.lastNode.type === domelementtype_1.ElementType.Comment) this.lastNode.data += data; else {
        var node = new node_js_1.Comment(data);
        this.addNode(node), this.lastNode = node;
      }
    }, DomHandler.prototype.oncommentend = function() {
      this.lastNode = null;
    }, DomHandler.prototype.oncdatastart = function() {
      var text = new node_js_1.Text(""), node = new node_js_1.CDATA([ text ]);
      this.addNode(node), text.parent = node, this.lastNode = text;
    }, DomHandler.prototype.oncdataend = function() {
      this.lastNode = null;
    }, DomHandler.prototype.onprocessinginstruction = function(name, data) {
      var node = new node_js_1.ProcessingInstruction(name, data);
      this.addNode(node);
    }, DomHandler.prototype.handleCallback = function(error) {
      if ("function" == typeof this.callback) this.callback(error, this.dom); else if (error) throw error;
    }, DomHandler.prototype.addNode = function(node) {
      var parent = this.tagStack[this.tagStack.length - 1], previousSibling = parent.children[parent.children.length - 1];
      this.options.withStartIndices && (node.startIndex = this.parser.startIndex), this.options.withEndIndices && (node.endIndex = this.parser.endIndex), 
      parent.children.push(node), previousSibling && (node.prev = previousSibling, previousSibling.next = node), 
      node.parent = parent, this.lastNode = null;
    }, DomHandler;
  }();
  exports.DomHandler = DomHandler, exports.default = DomHandler;
}(lib$4);

var lib$2 = {}, lib$1 = {}, decode$6 = {}, decodeDataHtml = {};

Object.defineProperty(decodeDataHtml, "__esModule", {
  value: !0
}), decodeDataHtml.default = new Uint16Array('ᵁ<Õıʊҝջאٵ۞ޢߖࠏ੊ઑඡ๭༉༦჊ረዡᐕᒝᓃᓟᔥ\0\0\0\0\0\0ᕫᛍᦍᰒᷝ὾⁠↰⊍⏀⏻⑂⠤⤒ⴈ⹈⿎〖㊺㘹㞬㣾㨨㩱㫠㬮ࠀEMabcfglmnoprstu\\bfms¦³¹ÈÏlig耻Æ䃆P耻&䀦cute耻Á䃁reve;䄂Āiyx}rc耻Â䃂;䐐r;쀀𝔄rave耻À䃀pha;䎑acr;䄀d;橓Āgp¡on;䄄f;쀀𝔸plyFunction;恡ing耻Å䃅Ācs¾Ãr;쀀𝒜ign;扔ilde耻Ã䃃ml耻Ä䃄ЀaceforsuåûþėĜĢħĪĀcrêòkslash;或Ŷöø;櫧ed;挆y;䐑ƀcrtąċĔause;戵noullis;愬a;䎒r;쀀𝔅pf;쀀𝔹eve;䋘còēmpeq;扎܀HOacdefhilorsuōőŖƀƞƢƵƷƺǜȕɳɸɾcy;䐧PY耻©䂩ƀcpyŝŢźute;䄆Ā;iŧŨ拒talDifferentialD;慅leys;愭ȀaeioƉƎƔƘron;䄌dil耻Ç䃇rc;䄈nint;戰ot;䄊ĀdnƧƭilla;䂸terDot;䂷òſi;䎧rcleȀDMPTǇǋǑǖot;抙inus;抖lus;投imes;抗oĀcsǢǸkwiseContourIntegral;戲eCurlyĀDQȃȏoubleQuote;思uote;怙ȀlnpuȞȨɇɕonĀ;eȥȦ户;橴ƀgitȯȶȺruent;扡nt;戯ourIntegral;戮ĀfrɌɎ;愂oduct;成nterClockwiseContourIntegral;戳oss;樯cr;쀀𝒞pĀ;Cʄʅ拓ap;才րDJSZacefiosʠʬʰʴʸˋ˗ˡ˦̳ҍĀ;oŹʥtrahd;椑cy;䐂cy;䐅cy;䐏ƀgrsʿ˄ˇger;怡r;憡hv;櫤Āayː˕ron;䄎;䐔lĀ;t˝˞戇a;䎔r;쀀𝔇Āaf˫̧Ācm˰̢riticalȀADGT̖̜̀̆cute;䂴oŴ̋̍;䋙bleAcute;䋝rave;䁠ilde;䋜ond;拄ferentialD;慆Ѱ̽\0\0\0͔͂\0Ѕf;쀀𝔻ƀ;DE͈͉͍䂨ot;惜qual;扐blèCDLRUVͣͲ΂ϏϢϸontourIntegraìȹoɴ͹\0\0ͻ»͉nArrow;懓Āeo·ΤftƀARTΐΖΡrrow;懐ightArrow;懔eåˊngĀLRΫτeftĀARγιrrow;柸ightArrow;柺ightArrow;柹ightĀATϘϞrrow;懒ee;抨pɁϩ\0\0ϯrrow;懑ownArrow;懕erticalBar;戥ǹABLRTaВЪаўѿͼrrowƀ;BUНОТ憓ar;椓pArrow;懵reve;䌑eft˒к\0ц\0ѐightVector;楐eeVector;楞ectorĀ;Bљњ憽ar;楖ightǔѧ\0ѱeeVector;楟ectorĀ;BѺѻ懁ar;楗eeĀ;A҆҇护rrow;憧ĀctҒҗr;쀀𝒟rok;䄐ࠀNTacdfglmopqstuxҽӀӄӋӞӢӧӮӵԡԯԶՒ՝ՠեG;䅊H耻Ð䃐cute耻É䃉ƀaiyӒӗӜron;䄚rc耻Ê䃊;䐭ot;䄖r;쀀𝔈rave耻È䃈ement;戈ĀapӺӾcr;䄒tyɓԆ\0\0ԒmallSquare;旻erySmallSquare;斫ĀgpԦԪon;䄘f;쀀𝔼silon;䎕uĀaiԼՉlĀ;TՂՃ橵ilde;扂librium;懌Āci՗՚r;愰m;橳a;䎗ml耻Ë䃋Āipժկsts;戃onentialE;慇ʀcfiosօֈ֍ֲ׌y;䐤r;쀀𝔉lledɓ֗\0\0֣mallSquare;旼erySmallSquare;斪Ͱֺ\0ֿ\0\0ׄf;쀀𝔽All;戀riertrf;愱cò׋؀JTabcdfgorstר׬ׯ׺؀ؒؖ؛؝أ٬ٲcy;䐃耻>䀾mmaĀ;d׷׸䎓;䏜reve;䄞ƀeiy؇،ؐdil;䄢rc;䄜;䐓ot;䄠r;쀀𝔊;拙pf;쀀𝔾eater̀EFGLSTصلَٖٛ٦qualĀ;Lؾؿ扥ess;招ullEqual;执reater;檢ess;扷lantEqual;橾ilde;扳cr;쀀𝒢;扫ЀAacfiosuڅڋږڛڞڪھۊRDcy;䐪Āctڐڔek;䋇;䁞irc;䄤r;愌lbertSpace;愋ǰگ\0ڲf;愍izontalLine;攀Āctۃۅòکrok;䄦mpńېۘownHumðįqual;扏܀EJOacdfgmnostuۺ۾܃܇܎ܚܞܡܨ݄ݸދޏޕcy;䐕lig;䄲cy;䐁cute耻Í䃍Āiyܓܘrc耻Î䃎;䐘ot;䄰r;愑rave耻Ì䃌ƀ;apܠܯܿĀcgܴܷr;䄪inaryI;慈lieóϝǴ݉\0ݢĀ;eݍݎ戬Āgrݓݘral;戫section;拂isibleĀCTݬݲomma;恣imes;恢ƀgptݿރވon;䄮f;쀀𝕀a;䎙cr;愐ilde;䄨ǫޚ\0ޞcy;䐆l耻Ï䃏ʀcfosuެ޷޼߂ߐĀiyޱ޵rc;䄴;䐙r;쀀𝔍pf;쀀𝕁ǣ߇\0ߌr;쀀𝒥rcy;䐈kcy;䐄΀HJacfosߤߨ߽߬߱ࠂࠈcy;䐥cy;䐌ppa;䎚Āey߶߻dil;䄶;䐚r;쀀𝔎pf;쀀𝕂cr;쀀𝒦րJTaceflmostࠥࠩࠬࡐࡣ঳সে্਷ੇcy;䐉耻<䀼ʀcmnpr࠷࠼ࡁࡄࡍute;䄹bda;䎛g;柪lacetrf;愒r;憞ƀaeyࡗ࡜ࡡron;䄽dil;䄻;䐛Āfsࡨ॰tԀACDFRTUVarࡾࢩࢱࣦ࣠ࣼयज़ΐ४Ānrࢃ࢏gleBracket;柨rowƀ;BR࢙࢚࢞憐ar;懤ightArrow;懆eiling;挈oǵࢷ\0ࣃbleBracket;柦nǔࣈ\0࣒eeVector;楡ectorĀ;Bࣛࣜ懃ar;楙loor;挊ightĀAV࣯ࣵrrow;憔ector;楎Āerँगeƀ;AVउऊऐ抣rrow;憤ector;楚iangleƀ;BEतथऩ抲ar;槏qual;抴pƀDTVषूौownVector;楑eeVector;楠ectorĀ;Bॖॗ憿ar;楘ectorĀ;B॥०憼ar;楒ightáΜs̀EFGLSTॾঋকঝঢভqualGreater;拚ullEqual;扦reater;扶ess;檡lantEqual;橽ilde;扲r;쀀𝔏Ā;eঽা拘ftarrow;懚idot;䄿ƀnpw৔ਖਛgȀLRlr৞৷ਂਐeftĀAR০৬rrow;柵ightArrow;柷ightArrow;柶eftĀarγਊightáοightáϊf;쀀𝕃erĀLRਢਬeftArrow;憙ightArrow;憘ƀchtਾੀੂòࡌ;憰rok;䅁;扪Ѐacefiosuਗ਼੝੠੷੼અઋ઎p;椅y;䐜Ādl੥੯iumSpace;恟lintrf;愳r;쀀𝔐nusPlus;戓pf;쀀𝕄cò੶;䎜ҀJacefostuણધભીଔଙඑ඗ඞcy;䐊cute;䅃ƀaey઴હાron;䅇dil;䅅;䐝ƀgswે૰଎ativeƀMTV૓૟૨ediumSpace;怋hiĀcn૦૘ë૙eryThiî૙tedĀGL૸ଆreaterGreateòٳessLesóੈLine;䀊r;쀀𝔑ȀBnptଢନଷ଺reak;恠BreakingSpace;䂠f;愕ڀ;CDEGHLNPRSTV୕ୖ୪୼஡௫ఄ౞಄ದ೘ൡඅ櫬Āou୛୤ngruent;扢pCap;扭oubleVerticalBar;戦ƀlqxஃஊ஛ement;戉ualĀ;Tஒஓ扠ilde;쀀≂̸ists;戄reater΀;EFGLSTஶஷ஽௉௓௘௥扯qual;扱ullEqual;쀀≧̸reater;쀀≫̸ess;批lantEqual;쀀⩾̸ilde;扵umpń௲௽ownHump;쀀≎̸qual;쀀≏̸eĀfsఊధtTriangleƀ;BEచఛడ拪ar;쀀⧏̸qual;括s̀;EGLSTవశ఼ౄోౘ扮qual;扰reater;扸ess;쀀≪̸lantEqual;쀀⩽̸ilde;扴estedĀGL౨౹reaterGreater;쀀⪢̸essLess;쀀⪡̸recedesƀ;ESಒಓಛ技qual;쀀⪯̸lantEqual;拠ĀeiಫಹverseElement;戌ghtTriangleƀ;BEೋೌ೒拫ar;쀀⧐̸qual;拭ĀquೝഌuareSuĀbp೨೹setĀ;E೰ೳ쀀⊏̸qual;拢ersetĀ;Eഃആ쀀⊐̸qual;拣ƀbcpഓതൎsetĀ;Eഛഞ쀀⊂⃒qual;抈ceedsȀ;ESTലള഻െ抁qual;쀀⪰̸lantEqual;拡ilde;쀀≿̸ersetĀ;E൘൛쀀⊃⃒qual;抉ildeȀ;EFT൮൯൵ൿ扁qual;扄ullEqual;扇ilde;扉erticalBar;戤cr;쀀𝒩ilde耻Ñ䃑;䎝܀Eacdfgmoprstuvලෂ෉෕ෛ෠෧෼ขภยา฿ไlig;䅒cute耻Ó䃓Āiy෎ීrc耻Ô䃔;䐞blac;䅐r;쀀𝔒rave耻Ò䃒ƀaei෮ෲ෶cr;䅌ga;䎩cron;䎟pf;쀀𝕆enCurlyĀDQฎบoubleQuote;怜uote;怘;橔Āclวฬr;쀀𝒪ash耻Ø䃘iŬื฼de耻Õ䃕es;樷ml耻Ö䃖erĀBP๋๠Āar๐๓r;怾acĀek๚๜;揞et;掴arenthesis;揜Ҁacfhilors๿ງຊຏຒດຝະ໼rtialD;戂y;䐟r;쀀𝔓i;䎦;䎠usMinus;䂱Āipຢອncareplanåڝf;愙Ȁ;eio຺ູ໠໤檻cedesȀ;EST່້໏໚扺qual;檯lantEqual;扼ilde;找me;怳Ādp໩໮uct;戏ortionĀ;aȥ໹l;戝Āci༁༆r;쀀𝒫;䎨ȀUfos༑༖༛༟OT耻"䀢r;쀀𝔔pf;愚cr;쀀𝒬؀BEacefhiorsu༾གྷཇའཱིྦྷྪྭ႖ႩႴႾarr;椐G耻®䂮ƀcnrཎནབute;䅔g;柫rĀ;tཛྷཝ憠l;椖ƀaeyཧཬཱron;䅘dil;䅖;䐠Ā;vླྀཹ愜erseĀEUྂྙĀlq྇ྎement;戋uilibrium;懋pEquilibrium;楯r»ཹo;䎡ghtЀACDFTUVa࿁࿫࿳ဢဨၛႇϘĀnr࿆࿒gleBracket;柩rowƀ;BL࿜࿝࿡憒ar;懥eftArrow;懄eiling;按oǵ࿹\0စbleBracket;柧nǔည\0နeeVector;楝ectorĀ;Bဝသ懂ar;楕loor;挋Āerိ၃eƀ;AVဵံြ抢rrow;憦ector;楛iangleƀ;BEၐၑၕ抳ar;槐qual;抵pƀDTVၣၮၸownVector;楏eeVector;楜ectorĀ;Bႂႃ憾ar;楔ectorĀ;B႑႒懀ar;楓Āpuႛ႞f;愝ndImplies;楰ightarrow;懛ĀchႹႼr;愛;憱leDelayed;槴ڀHOacfhimoqstuფჱჷჽᄙᄞᅑᅖᅡᅧᆵᆻᆿĀCcჩხHcy;䐩y;䐨FTcy;䐬cute;䅚ʀ;aeiyᄈᄉᄎᄓᄗ檼ron;䅠dil;䅞rc;䅜;䐡r;쀀𝔖ortȀDLRUᄪᄴᄾᅉownArrow»ОeftArrow»࢚ightArrow»࿝pArrow;憑gma;䎣allCircle;战pf;쀀𝕊ɲᅭ\0\0ᅰt;戚areȀ;ISUᅻᅼᆉᆯ斡ntersection;抓uĀbpᆏᆞsetĀ;Eᆗᆘ抏qual;抑ersetĀ;Eᆨᆩ抐qual;抒nion;抔cr;쀀𝒮ar;拆ȀbcmpᇈᇛሉላĀ;sᇍᇎ拐etĀ;Eᇍᇕqual;抆ĀchᇠህeedsȀ;ESTᇭᇮᇴᇿ扻qual;檰lantEqual;扽ilde;承Tháྌ;我ƀ;esሒሓሣ拑rsetĀ;Eሜም抃qual;抇et»ሓրHRSacfhiorsሾቄ቉ቕ቞ቱቶኟዂወዑORN耻Þ䃞ADE;愢ĀHc቎ቒcy;䐋y;䐦Ābuቚቜ;䀉;䎤ƀaeyብቪቯron;䅤dil;䅢;䐢r;쀀𝔗Āeiቻ኉ǲኀ\0ኇefore;戴a;䎘Ācn኎ኘkSpace;쀀  Space;怉ldeȀ;EFTካኬኲኼ戼qual;扃ullEqual;扅ilde;扈pf;쀀𝕋ipleDot;惛Āctዖዛr;쀀𝒯rok;䅦ૡዷጎጚጦ\0ጬጱ\0\0\0\0\0ጸጽ፷ᎅ\0᏿ᐄᐊᐐĀcrዻጁute耻Ú䃚rĀ;oጇገ憟cir;楉rǣጓ\0጖y;䐎ve;䅬Āiyጞጣrc耻Û䃛;䐣blac;䅰r;쀀𝔘rave耻Ù䃙acr;䅪Ādiፁ፩erĀBPፈ፝Āarፍፐr;䁟acĀekፗፙ;揟et;掵arenthesis;揝onĀ;P፰፱拃lus;抎Āgp፻፿on;䅲f;쀀𝕌ЀADETadps᎕ᎮᎸᏄϨᏒᏗᏳrrowƀ;BDᅐᎠᎤar;椒ownArrow;懅ownArrow;憕quilibrium;楮eeĀ;AᏋᏌ报rrow;憥ownáϳerĀLRᏞᏨeftArrow;憖ightArrow;憗iĀ;lᏹᏺ䏒on;䎥ing;䅮cr;쀀𝒰ilde;䅨ml耻Ü䃜ҀDbcdefosvᐧᐬᐰᐳᐾᒅᒊᒐᒖash;披ar;櫫y;䐒ashĀ;lᐻᐼ抩;櫦Āerᑃᑅ;拁ƀbtyᑌᑐᑺar;怖Ā;iᑏᑕcalȀBLSTᑡᑥᑪᑴar;戣ine;䁼eparator;杘ilde;所ThinSpace;怊r;쀀𝔙pf;쀀𝕍cr;쀀𝒱dash;抪ʀcefosᒧᒬᒱᒶᒼirc;䅴dge;拀r;쀀𝔚pf;쀀𝕎cr;쀀𝒲Ȁfiosᓋᓐᓒᓘr;쀀𝔛;䎞pf;쀀𝕏cr;쀀𝒳ҀAIUacfosuᓱᓵᓹᓽᔄᔏᔔᔚᔠcy;䐯cy;䐇cy;䐮cute耻Ý䃝Āiyᔉᔍrc;䅶;䐫r;쀀𝔜pf;쀀𝕐cr;쀀𝒴ml;䅸ЀHacdefosᔵᔹᔿᕋᕏᕝᕠᕤcy;䐖cute;䅹Āayᕄᕉron;䅽;䐗ot;䅻ǲᕔ\0ᕛoWidtè૙a;䎖r;愨pf;愤cr;쀀𝒵௡ᖃᖊᖐ\0ᖰᖶᖿ\0\0\0\0ᗆᗛᗫᙟ᙭\0ᚕ᚛ᚲᚹ\0ᚾcute耻á䃡reve;䄃̀;Ediuyᖜᖝᖡᖣᖨᖭ戾;쀀∾̳;房rc耻â䃢te肻´̆;䐰lig耻æ䃦Ā;r²ᖺ;쀀𝔞rave耻à䃠ĀepᗊᗖĀfpᗏᗔsym;愵èᗓha;䎱ĀapᗟcĀclᗤᗧr;䄁g;樿ɤᗰ\0\0ᘊʀ;adsvᗺᗻᗿᘁᘇ戧nd;橕;橜lope;橘;橚΀;elmrszᘘᘙᘛᘞᘿᙏᙙ戠;榤e»ᘙsdĀ;aᘥᘦ戡ѡᘰᘲᘴᘶᘸᘺᘼᘾ;榨;榩;榪;榫;榬;榭;榮;榯tĀ;vᙅᙆ戟bĀ;dᙌᙍ抾;榝Āptᙔᙗh;戢»¹arr;捼Āgpᙣᙧon;䄅f;쀀𝕒΀;Eaeiop዁ᙻᙽᚂᚄᚇᚊ;橰cir;橯;扊d;手s;䀧roxĀ;e዁ᚒñᚃing耻å䃥ƀctyᚡᚦᚨr;쀀𝒶;䀪mpĀ;e዁ᚯñʈilde耻ã䃣ml耻ä䃤Āciᛂᛈoninôɲnt;樑ࠀNabcdefiklnoprsu᛭ᛱᜰ᜼ᝃᝈ᝸᝽០៦ᠹᡐᜍ᤽᥈ᥰot;櫭Ācrᛶ᜞kȀcepsᜀᜅᜍᜓong;扌psilon;䏶rime;怵imĀ;e᜚᜛戽q;拍Ŷᜢᜦee;抽edĀ;gᜬᜭ挅e»ᜭrkĀ;t፜᜷brk;掶Āoyᜁᝁ;䐱quo;怞ʀcmprtᝓ᝛ᝡᝤᝨausĀ;eĊĉptyv;榰séᜌnoõēƀahwᝯ᝱ᝳ;䎲;愶een;扬r;쀀𝔟g΀costuvwឍឝឳេ៕៛៞ƀaiuបពរðݠrc;旯p»፱ƀdptឤឨឭot;樀lus;樁imes;樂ɱឹ\0\0ើcup;樆ar;昅riangleĀdu៍្own;施p;斳plus;樄eåᑄåᒭarow;植ƀako៭ᠦᠵĀcn៲ᠣkƀlst៺֫᠂ozenge;槫riangleȀ;dlr᠒᠓᠘᠝斴own;斾eft;旂ight;斸k;搣Ʊᠫ\0ᠳƲᠯ\0ᠱ;斒;斑4;斓ck;斈ĀeoᠾᡍĀ;qᡃᡆ쀀=⃥uiv;쀀≡⃥t;挐Ȁptwxᡙᡞᡧᡬf;쀀𝕓Ā;tᏋᡣom»Ꮜtie;拈؀DHUVbdhmptuvᢅᢖᢪᢻᣗᣛᣬ᣿ᤅᤊᤐᤡȀLRlrᢎᢐᢒᢔ;敗;敔;敖;敓ʀ;DUduᢡᢢᢤᢦᢨ敐;敦;敩;敤;敧ȀLRlrᢳᢵᢷᢹ;敝;敚;敜;教΀;HLRhlrᣊᣋᣍᣏᣑᣓᣕ救;敬;散;敠;敫;敢;敟ox;槉ȀLRlrᣤᣦᣨᣪ;敕;敒;攐;攌ʀ;DUduڽ᣷᣹᣻᣽;敥;敨;攬;攴inus;抟lus;択imes;抠ȀLRlrᤙᤛᤝ᤟;敛;敘;攘;攔΀;HLRhlrᤰᤱᤳᤵᤷ᤻᤹攂;敪;敡;敞;攼;攤;攜Āevģ᥂bar耻¦䂦Ȁceioᥑᥖᥚᥠr;쀀𝒷mi;恏mĀ;e᜚᜜lƀ;bhᥨᥩᥫ䁜;槅sub;柈Ŭᥴ᥾lĀ;e᥹᥺怢t»᥺pƀ;Eeįᦅᦇ;檮Ā;qۜۛೡᦧ\0᧨ᨑᨕᨲ\0ᨷᩐ\0\0᪴\0\0᫁\0\0ᬡᬮ᭍᭒\0᯽\0ᰌƀcpr᦭ᦲ᧝ute;䄇̀;abcdsᦿᧀᧄ᧊᧕᧙戩nd;橄rcup;橉Āau᧏᧒p;橋p;橇ot;橀;쀀∩︀Āeo᧢᧥t;恁îړȀaeiu᧰᧻ᨁᨅǰ᧵\0᧸s;橍on;䄍dil耻ç䃧rc;䄉psĀ;sᨌᨍ橌m;橐ot;䄋ƀdmnᨛᨠᨦil肻¸ƭptyv;榲t脀¢;eᨭᨮ䂢räƲr;쀀𝔠ƀceiᨽᩀᩍy;䑇ckĀ;mᩇᩈ朓ark»ᩈ;䏇r΀;Ecefms᩟᩠ᩢᩫ᪤᪪᪮旋;槃ƀ;elᩩᩪᩭ䋆q;扗eɡᩴ\0\0᪈rrowĀlr᩼᪁eft;憺ight;憻ʀRSacd᪒᪔᪖᪚᪟»ཇ;擈st;抛irc;抚ash;抝nint;樐id;櫯cir;槂ubsĀ;u᪻᪼晣it»᪼ˬ᫇᫔᫺\0ᬊonĀ;eᫍᫎ䀺Ā;qÇÆɭ᫙\0\0᫢aĀ;t᫞᫟䀬;䁀ƀ;fl᫨᫩᫫戁îᅠeĀmx᫱᫶ent»᫩eóɍǧ᫾\0ᬇĀ;dኻᬂot;橭nôɆƀfryᬐᬔᬗ;쀀𝕔oäɔ脀©;sŕᬝr;愗Āaoᬥᬩrr;憵ss;朗Ācuᬲᬷr;쀀𝒸Ābpᬼ᭄Ā;eᭁᭂ櫏;櫑Ā;eᭉᭊ櫐;櫒dot;拯΀delprvw᭠᭬᭷ᮂᮬᯔ᯹arrĀlr᭨᭪;椸;椵ɰ᭲\0\0᭵r;拞c;拟arrĀ;p᭿ᮀ憶;椽̀;bcdosᮏᮐᮖᮡᮥᮨ截rcap;橈Āauᮛᮞp;橆p;橊ot;抍r;橅;쀀∪︀Ȁalrv᮵ᮿᯞᯣrrĀ;mᮼᮽ憷;椼yƀevwᯇᯔᯘqɰᯎ\0\0ᯒreã᭳uã᭵ee;拎edge;拏en耻¤䂤earrowĀlrᯮ᯳eft»ᮀight»ᮽeäᯝĀciᰁᰇoninôǷnt;戱lcty;挭ঀAHabcdefhijlorstuwz᰸᰻᰿ᱝᱩᱵᲊᲞᲬᲷ᳻᳿ᴍᵻᶑᶫᶻ᷆᷍rò΁ar;楥Ȁglrs᱈ᱍ᱒᱔ger;怠eth;愸òᄳhĀ;vᱚᱛ怐»ऊūᱡᱧarow;椏aã̕Āayᱮᱳron;䄏;䐴ƀ;ao̲ᱼᲄĀgrʿᲁr;懊tseq;橷ƀglmᲑᲔᲘ耻°䂰ta;䎴ptyv;榱ĀirᲣᲨsht;楿;쀀𝔡arĀlrᲳᲵ»ࣜ»သʀaegsv᳂͸᳖᳜᳠mƀ;oș᳊᳔ndĀ;ș᳑uit;晦amma;䏝in;拲ƀ;io᳧᳨᳸䃷de脀÷;o᳧ᳰntimes;拇nø᳷cy;䑒cɯᴆ\0\0ᴊrn;挞op;挍ʀlptuwᴘᴝᴢᵉᵕlar;䀤f;쀀𝕕ʀ;emps̋ᴭᴷᴽᵂqĀ;d͒ᴳot;扑inus;戸lus;戔quare;抡blebarwedgåúnƀadhᄮᵝᵧownarrowóᲃarpoonĀlrᵲᵶefôᲴighôᲶŢᵿᶅkaro÷གɯᶊ\0\0ᶎrn;挟op;挌ƀcotᶘᶣᶦĀryᶝᶡ;쀀𝒹;䑕l;槶rok;䄑Ādrᶰᶴot;拱iĀ;fᶺ᠖斿Āah᷀᷃ròЩaòྦangle;榦Āci᷒ᷕy;䑟grarr;柿ऀDacdefglmnopqrstuxḁḉḙḸոḼṉṡṾấắẽỡἪἷὄ὎὚ĀDoḆᴴoôᲉĀcsḎḔute耻é䃩ter;橮ȀaioyḢḧḱḶron;䄛rĀ;cḭḮ扖耻ê䃪lon;払;䑍ot;䄗ĀDrṁṅot;扒;쀀𝔢ƀ;rsṐṑṗ檚ave耻è䃨Ā;dṜṝ檖ot;檘Ȁ;ilsṪṫṲṴ檙nters;揧;愓Ā;dṹṺ檕ot;檗ƀapsẅẉẗcr;䄓tyƀ;svẒẓẕ戅et»ẓpĀ1;ẝẤĳạả;怄;怅怃ĀgsẪẬ;䅋p;怂ĀgpẴẸon;䄙f;쀀𝕖ƀalsỄỎỒrĀ;sỊị拕l;槣us;橱iƀ;lvỚớở䎵on»ớ;䏵ȀcsuvỪỳἋἣĀioữḱrc»Ḯɩỹ\0\0ỻíՈantĀglἂἆtr»ṝess»Ṻƀaeiἒ἖Ἒls;䀽st;扟vĀ;DȵἠD;橸parsl;槥ĀDaἯἳot;打rr;楱ƀcdiἾὁỸr;愯oô͒ĀahὉὋ;䎷耻ð䃰Āmrὓὗl耻ë䃫o;悬ƀcipὡὤὧl;䀡sôծĀeoὬὴctatioîՙnentialåչৡᾒ\0ᾞ\0ᾡᾧ\0\0ῆῌ\0ΐ\0ῦῪ \0 ⁚llingdotseñṄy;䑄male;晀ƀilrᾭᾳ῁lig;耀ﬃɩᾹ\0\0᾽g;耀ﬀig;耀ﬄ;쀀𝔣lig;耀ﬁlig;쀀fjƀaltῙ῜ῡt;晭ig;耀ﬂns;斱of;䆒ǰ΅\0ῳf;쀀𝕗ĀakֿῷĀ;vῼ´拔;櫙artint;樍Āao‌⁕Ācs‑⁒α‚‰‸⁅⁈\0⁐β•‥‧‪‬\0‮耻½䂽;慓耻¼䂼;慕;慙;慛Ƴ‴\0‶;慔;慖ʴ‾⁁\0\0⁃耻¾䂾;慗;慜5;慘ƶ⁌\0⁎;慚;慝8;慞l;恄wn;挢cr;쀀𝒻ࢀEabcdefgijlnorstv₂₉₟₥₰₴⃰⃵⃺⃿℃ℒℸ̗ℾ⅒↞Ā;lٍ₇;檌ƀcmpₐₕ₝ute;䇵maĀ;dₜ᳚䎳;檆reve;䄟Āiy₪₮rc;䄝;䐳ot;䄡Ȁ;lqsؾق₽⃉ƀ;qsؾٌ⃄lanô٥Ȁ;cdl٥⃒⃥⃕c;檩otĀ;o⃜⃝檀Ā;l⃢⃣檂;檄Ā;e⃪⃭쀀⋛︀s;檔r;쀀𝔤Ā;gٳ؛mel;愷cy;䑓Ȁ;Eajٚℌℎℐ;檒;檥;檤ȀEaesℛℝ℩ℴ;扩pĀ;p℣ℤ檊rox»ℤĀ;q℮ℯ檈Ā;q℮ℛim;拧pf;쀀𝕘Āci⅃ⅆr;愊mƀ;el٫ⅎ⅐;檎;檐茀>;cdlqr׮ⅠⅪⅮⅳⅹĀciⅥⅧ;檧r;橺ot;拗Par;榕uest;橼ʀadelsↄⅪ←ٖ↛ǰ↉\0↎proø₞r;楸qĀlqؿ↖lesó₈ií٫Āen↣↭rtneqq;쀀≩︀Å↪ԀAabcefkosy⇄⇇⇱⇵⇺∘∝∯≨≽ròΠȀilmr⇐⇔⇗⇛rsðᒄf»․ilôکĀdr⇠⇤cy;䑊ƀ;cwࣴ⇫⇯ir;楈;憭ar;意irc;䄥ƀalr∁∎∓rtsĀ;u∉∊晥it»∊lip;怦con;抹r;쀀𝔥sĀew∣∩arow;椥arow;椦ʀamopr∺∾≃≞≣rr;懿tht;戻kĀlr≉≓eftarrow;憩ightarrow;憪f;쀀𝕙bar;怕ƀclt≯≴≸r;쀀𝒽asè⇴rok;䄧Ābp⊂⊇ull;恃hen»ᱛૡ⊣\0⊪\0⊸⋅⋎\0⋕⋳\0\0⋸⌢⍧⍢⍿\0⎆⎪⎴cute耻í䃭ƀ;iyݱ⊰⊵rc耻î䃮;䐸Ācx⊼⊿y;䐵cl耻¡䂡ĀfrΟ⋉;쀀𝔦rave耻ì䃬Ȁ;inoܾ⋝⋩⋮Āin⋢⋦nt;樌t;戭fin;槜ta;愩lig;䄳ƀaop⋾⌚⌝ƀcgt⌅⌈⌗r;䄫ƀelpܟ⌏⌓inåގarôܠh;䄱f;抷ed;䆵ʀ;cfotӴ⌬⌱⌽⍁are;愅inĀ;t⌸⌹戞ie;槝doô⌙ʀ;celpݗ⍌⍐⍛⍡al;抺Āgr⍕⍙eróᕣã⍍arhk;樗rod;樼Ȁcgpt⍯⍲⍶⍻y;䑑on;䄯f;쀀𝕚a;䎹uest耻¿䂿Āci⎊⎏r;쀀𝒾nʀ;EdsvӴ⎛⎝⎡ӳ;拹ot;拵Ā;v⎦⎧拴;拳Ā;iݷ⎮lde;䄩ǫ⎸\0⎼cy;䑖l耻ï䃯̀cfmosu⏌⏗⏜⏡⏧⏵Āiy⏑⏕rc;䄵;䐹r;쀀𝔧ath;䈷pf;쀀𝕛ǣ⏬\0⏱r;쀀𝒿rcy;䑘kcy;䑔Ѐacfghjos␋␖␢␧␭␱␵␻ppaĀ;v␓␔䎺;䏰Āey␛␠dil;䄷;䐺r;쀀𝔨reen;䄸cy;䑅cy;䑜pf;쀀𝕜cr;쀀𝓀஀ABEHabcdefghjlmnoprstuv⑰⒁⒆⒍⒑┎┽╚▀♎♞♥♹♽⚚⚲⛘❝❨➋⟀⠁⠒ƀart⑷⑺⑼rò৆òΕail;椛arr;椎Ā;gঔ⒋;檋ar;楢ॣ⒥\0⒪\0⒱\0\0\0\0\0⒵Ⓔ\0ⓆⓈⓍ\0⓹ute;䄺mptyv;榴raîࡌbda;䎻gƀ;dlࢎⓁⓃ;榑åࢎ;檅uo耻«䂫rЀ;bfhlpst࢙ⓞⓦⓩ⓫⓮⓱⓵Ā;f࢝ⓣs;椟s;椝ë≒p;憫l;椹im;楳l;憢ƀ;ae⓿─┄檫il;椙Ā;s┉┊檭;쀀⪭︀ƀabr┕┙┝rr;椌rk;杲Āak┢┬cĀek┨┪;䁻;䁛Āes┱┳;榋lĀdu┹┻;榏;榍Ȁaeuy╆╋╖╘ron;䄾Ādi═╔il;䄼ìࢰâ┩;䐻Ȁcqrs╣╦╭╽a;椶uoĀ;rนᝆĀdu╲╷har;楧shar;楋h;憲ʀ;fgqs▋▌উ◳◿扤tʀahlrt▘▤▷◂◨rrowĀ;t࢙□aé⓶arpoonĀdu▯▴own»њp»०eftarrows;懇ightƀahs◍◖◞rrowĀ;sࣴࢧarpoonó྘quigarro÷⇰hreetimes;拋ƀ;qs▋ও◺lanôবʀ;cdgsব☊☍☝☨c;檨otĀ;o☔☕橿Ā;r☚☛檁;檃Ā;e☢☥쀀⋚︀s;檓ʀadegs☳☹☽♉♋pproøⓆot;拖qĀgq♃♅ôউgtò⒌ôছiíলƀilr♕࣡♚sht;楼;쀀𝔩Ā;Eজ♣;檑š♩♶rĀdu▲♮Ā;l॥♳;楪lk;斄cy;䑙ʀ;achtੈ⚈⚋⚑⚖rò◁orneòᴈard;楫ri;旺Āio⚟⚤dot;䅀ustĀ;a⚬⚭掰che»⚭ȀEaes⚻⚽⛉⛔;扨pĀ;p⛃⛄檉rox»⛄Ā;q⛎⛏檇Ā;q⛎⚻im;拦Ѐabnoptwz⛩⛴⛷✚✯❁❇❐Ānr⛮⛱g;柬r;懽rëࣁgƀlmr⛿✍✔eftĀar০✇ightá৲apsto;柼ightá৽parrowĀlr✥✩efô⓭ight;憬ƀafl✶✹✽r;榅;쀀𝕝us;樭imes;樴š❋❏st;戗áፎƀ;ef❗❘᠀旊nge»❘arĀ;l❤❥䀨t;榓ʀachmt❳❶❼➅➇ròࢨorneòᶌarĀ;d྘➃;業;怎ri;抿̀achiqt➘➝ੀ➢➮➻quo;怹r;쀀𝓁mƀ;egল➪➬;檍;檏Ābu┪➳oĀ;rฟ➹;怚rok;䅂萀<;cdhilqrࠫ⟒☹⟜⟠⟥⟪⟰Āci⟗⟙;檦r;橹reå◲mes;拉arr;楶uest;橻ĀPi⟵⟹ar;榖ƀ;ef⠀भ᠛旃rĀdu⠇⠍shar;楊har;楦Āen⠗⠡rtneqq;쀀≨︀Å⠞܀Dacdefhilnopsu⡀⡅⢂⢎⢓⢠⢥⢨⣚⣢⣤ઃ⣳⤂Dot;戺Ȁclpr⡎⡒⡣⡽r耻¯䂯Āet⡗⡙;時Ā;e⡞⡟朠se»⡟Ā;sျ⡨toȀ;dluျ⡳⡷⡻owîҌefôएðᏑker;斮Āoy⢇⢌mma;権;䐼ash;怔asuredangle»ᘦr;쀀𝔪o;愧ƀcdn⢯⢴⣉ro耻µ䂵Ȁ;acdᑤ⢽⣀⣄sôᚧir;櫰ot肻·Ƶusƀ;bd⣒ᤃ⣓戒Ā;uᴼ⣘;横ţ⣞⣡p;櫛ò−ðઁĀdp⣩⣮els;抧f;쀀𝕞Āct⣸⣽r;쀀𝓂pos»ᖝƀ;lm⤉⤊⤍䎼timap;抸ఀGLRVabcdefghijlmoprstuvw⥂⥓⥾⦉⦘⧚⧩⨕⨚⩘⩝⪃⪕⪤⪨⬄⬇⭄⭿⮮ⰴⱧⱼ⳩Āgt⥇⥋;쀀⋙̸Ā;v⥐௏쀀≫⃒ƀelt⥚⥲⥶ftĀar⥡⥧rrow;懍ightarrow;懎;쀀⋘̸Ā;v⥻ే쀀≪⃒ightarrow;懏ĀDd⦎⦓ash;抯ash;抮ʀbcnpt⦣⦧⦬⦱⧌la»˞ute;䅄g;쀀∠⃒ʀ;Eiop඄⦼⧀⧅⧈;쀀⩰̸d;쀀≋̸s;䅉roø඄urĀ;a⧓⧔普lĀ;s⧓ସǳ⧟\0⧣p肻 ଷmpĀ;e௹ఀʀaeouy⧴⧾⨃⨐⨓ǰ⧹\0⧻;橃on;䅈dil;䅆ngĀ;dൾ⨊ot;쀀⩭̸p;橂;䐽ash;怓΀;Aadqsxஒ⨩⨭⨻⩁⩅⩐rr;懗rĀhr⨳⨶k;椤Ā;oᏲᏰot;쀀≐̸uiöୣĀei⩊⩎ar;椨í஘istĀ;s஠டr;쀀𝔫ȀEest௅⩦⩹⩼ƀ;qs஼⩭௡ƀ;qs஼௅⩴lanô௢ií௪Ā;rஶ⪁»ஷƀAap⪊⪍⪑rò⥱rr;憮ar;櫲ƀ;svྍ⪜ྌĀ;d⪡⪢拼;拺cy;䑚΀AEadest⪷⪺⪾⫂⫅⫶⫹rò⥦;쀀≦̸rr;憚r;急Ȁ;fqs఻⫎⫣⫯tĀar⫔⫙rro÷⫁ightarro÷⪐ƀ;qs఻⪺⫪lanôౕĀ;sౕ⫴»శiíౝĀ;rవ⫾iĀ;eచథiäඐĀpt⬌⬑f;쀀𝕟膀¬;in⬙⬚⬶䂬nȀ;Edvஉ⬤⬨⬮;쀀⋹̸ot;쀀⋵̸ǡஉ⬳⬵;拷;拶iĀ;vಸ⬼ǡಸ⭁⭃;拾;拽ƀaor⭋⭣⭩rȀ;ast୻⭕⭚⭟lleì୻l;쀀⫽⃥;쀀∂̸lint;樔ƀ;ceಒ⭰⭳uåಥĀ;cಘ⭸Ā;eಒ⭽ñಘȀAait⮈⮋⮝⮧rò⦈rrƀ;cw⮔⮕⮙憛;쀀⤳̸;쀀↝̸ghtarrow»⮕riĀ;eೋೖ΀chimpqu⮽⯍⯙⬄୸⯤⯯Ȁ;cerല⯆ഷ⯉uå൅;쀀𝓃ortɭ⬅\0\0⯖ará⭖mĀ;e൮⯟Ā;q൴൳suĀbp⯫⯭å೸åഋƀbcp⯶ⰑⰙȀ;Ees⯿ⰀഢⰄ抄;쀀⫅̸etĀ;eഛⰋqĀ;qണⰀcĀ;eലⰗñസȀ;EesⰢⰣൟⰧ抅;쀀⫆̸etĀ;e൘ⰮqĀ;qൠⰣȀgilrⰽⰿⱅⱇìௗlde耻ñ䃱çృiangleĀlrⱒⱜeftĀ;eచⱚñదightĀ;eೋⱥñ೗Ā;mⱬⱭ䎽ƀ;esⱴⱵⱹ䀣ro;愖p;怇ҀDHadgilrsⲏⲔⲙⲞⲣⲰⲶⳓⳣash;抭arr;椄p;쀀≍⃒ash;抬ĀetⲨⲬ;쀀≥⃒;쀀>⃒nfin;槞ƀAetⲽⳁⳅrr;椂;쀀≤⃒Ā;rⳊⳍ쀀<⃒ie;쀀⊴⃒ĀAtⳘⳜrr;椃rie;쀀⊵⃒im;쀀∼⃒ƀAan⳰⳴ⴂrr;懖rĀhr⳺⳽k;椣Ā;oᏧᏥear;椧ቓ᪕\0\0\0\0\0\0\0\0\0\0\0\0\0ⴭ\0ⴸⵈⵠⵥ⵲ⶄᬇ\0\0ⶍⶫ\0ⷈⷎ\0ⷜ⸙⸫⸾⹃Ācsⴱ᪗ute耻ó䃳ĀiyⴼⵅrĀ;c᪞ⵂ耻ô䃴;䐾ʀabios᪠ⵒⵗǈⵚlac;䅑v;樸old;榼lig;䅓Ācr⵩⵭ir;榿;쀀𝔬ͯ⵹\0\0⵼\0ⶂn;䋛ave耻ò䃲;槁Ābmⶈ෴ar;榵Ȁacitⶕ⶘ⶥⶨrò᪀Āir⶝ⶠr;榾oss;榻nå๒;槀ƀaeiⶱⶵⶹcr;䅍ga;䏉ƀcdnⷀⷅǍron;䎿;榶pf;쀀𝕠ƀaelⷔ⷗ǒr;榷rp;榹΀;adiosvⷪⷫⷮ⸈⸍⸐⸖戨rò᪆Ȁ;efmⷷⷸ⸂⸅橝rĀ;oⷾⷿ愴f»ⷿ耻ª䂪耻º䂺gof;抶r;橖lope;橗;橛ƀclo⸟⸡⸧ò⸁ash耻ø䃸l;折iŬⸯ⸴de耻õ䃵esĀ;aǛ⸺s;樶ml耻ö䃶bar;挽ૡ⹞\0⹽\0⺀⺝\0⺢⺹\0\0⻋ຜ\0⼓\0\0⼫⾼\0⿈rȀ;astЃ⹧⹲຅脀¶;l⹭⹮䂶leìЃɩ⹸\0\0⹻m;櫳;櫽y;䐿rʀcimpt⺋⺏⺓ᡥ⺗nt;䀥od;䀮il;怰enk;怱r;쀀𝔭ƀimo⺨⺰⺴Ā;v⺭⺮䏆;䏕maô੶ne;明ƀ;tv⺿⻀⻈䏀chfork»´;䏖Āau⻏⻟nĀck⻕⻝kĀ;h⇴⻛;愎ö⇴sҀ;abcdemst⻳⻴ᤈ⻹⻽⼄⼆⼊⼎䀫cir;樣ir;樢Āouᵀ⼂;樥;橲n肻±ຝim;樦wo;樧ƀipu⼙⼠⼥ntint;樕f;쀀𝕡nd耻£䂣Ԁ;Eaceinosu່⼿⽁⽄⽇⾁⾉⾒⽾⾶;檳p;檷uå໙Ā;c໎⽌̀;acens່⽙⽟⽦⽨⽾pproø⽃urlyeñ໙ñ໎ƀaes⽯⽶⽺pprox;檹qq;檵im;拨iíໟmeĀ;s⾈ຮ怲ƀEas⽸⾐⽺ð⽵ƀdfp໬⾙⾯ƀals⾠⾥⾪lar;挮ine;挒urf;挓Ā;t໻⾴ï໻rel;抰Āci⿀⿅r;쀀𝓅;䏈ncsp;怈̀fiopsu⿚⋢⿟⿥⿫⿱r;쀀𝔮pf;쀀𝕢rime;恗cr;쀀𝓆ƀaeo⿸〉〓tĀei⿾々rnionóڰnt;樖stĀ;e【】䀿ñἙô༔઀ABHabcdefhilmnoprstux぀けさすムㄎㄫㅇㅢㅲㆎ㈆㈕㈤㈩㉘㉮㉲㊐㊰㊷ƀartぇおがròႳòϝail;検aròᱥar;楤΀cdenqrtとふへみわゔヌĀeuねぱ;쀀∽̱te;䅕iãᅮmptyv;榳gȀ;del࿑らるろ;榒;榥å࿑uo耻»䂻rր;abcfhlpstw࿜ガクシスゼゾダッデナp;極Ā;f࿠ゴs;椠;椳s;椞ë≝ð✮l;楅im;楴l;憣;憝Āaiパフil;椚oĀ;nホボ戶aló༞ƀabrョリヮrò៥rk;杳ĀakンヽcĀekヹ・;䁽;䁝Āes㄂㄄;榌lĀduㄊㄌ;榎;榐Ȁaeuyㄗㄜㄧㄩron;䅙Ādiㄡㄥil;䅗ì࿲âヺ;䑀Ȁclqsㄴㄷㄽㅄa;椷dhar;楩uoĀ;rȎȍh;憳ƀacgㅎㅟངlȀ;ipsླྀㅘㅛႜnåႻarôྩt;断ƀilrㅩဣㅮsht;楽;쀀𝔯ĀaoㅷㆆrĀduㅽㅿ»ѻĀ;l႑ㆄ;楬Ā;vㆋㆌ䏁;䏱ƀgns㆕ㇹㇼht̀ahlrstㆤㆰ㇂㇘㇤㇮rrowĀ;t࿜ㆭaéトarpoonĀduㆻㆿowîㅾp»႒eftĀah㇊㇐rrowó࿪arpoonóՑightarrows;應quigarro÷ニhreetimes;拌g;䋚ingdotseñἲƀahm㈍㈐㈓rò࿪aòՑ;怏oustĀ;a㈞㈟掱che»㈟mid;櫮Ȁabpt㈲㈽㉀㉒Ānr㈷㈺g;柭r;懾rëဃƀafl㉇㉊㉎r;榆;쀀𝕣us;樮imes;樵Āap㉝㉧rĀ;g㉣㉤䀩t;榔olint;樒arò㇣Ȁachq㉻㊀Ⴜ㊅quo;怺r;쀀𝓇Ābu・㊊oĀ;rȔȓƀhir㊗㊛㊠reåㇸmes;拊iȀ;efl㊪ၙᠡ㊫方tri;槎luhar;楨;愞ൡ㋕㋛㋟㌬㌸㍱\0㍺㎤\0\0㏬㏰\0㐨㑈㑚㒭㒱㓊㓱\0㘖\0\0㘳cute;䅛quï➺Ԁ;Eaceinpsyᇭ㋳㋵㋿㌂㌋㌏㌟㌦㌩;檴ǰ㋺\0㋼;檸on;䅡uåᇾĀ;dᇳ㌇il;䅟rc;䅝ƀEas㌖㌘㌛;檶p;檺im;择olint;樓iíሄ;䑁otƀ;be㌴ᵇ㌵担;橦΀Aacmstx㍆㍊㍗㍛㍞㍣㍭rr;懘rĀhr㍐㍒ë∨Ā;oਸ਼਴t耻§䂧i;䀻war;椩mĀin㍩ðnuóñt;朶rĀ;o㍶⁕쀀𝔰Ȁacoy㎂㎆㎑㎠rp;景Āhy㎋㎏cy;䑉;䑈rtɭ㎙\0\0㎜iäᑤaraì⹯耻­䂭Āgm㎨㎴maƀ;fv㎱㎲㎲䏃;䏂Ѐ;deglnprካ㏅㏉㏎㏖㏞㏡㏦ot;橪Ā;q኱ኰĀ;E㏓㏔檞;檠Ā;E㏛㏜檝;檟e;扆lus;樤arr;楲aròᄽȀaeit㏸㐈㐏㐗Āls㏽㐄lsetmé㍪hp;樳parsl;槤Ādlᑣ㐔e;挣Ā;e㐜㐝檪Ā;s㐢㐣檬;쀀⪬︀ƀflp㐮㐳㑂tcy;䑌Ā;b㐸㐹䀯Ā;a㐾㐿槄r;挿f;쀀𝕤aĀdr㑍ЂesĀ;u㑔㑕晠it»㑕ƀcsu㑠㑹㒟Āau㑥㑯pĀ;sᆈ㑫;쀀⊓︀pĀ;sᆴ㑵;쀀⊔︀uĀbp㑿㒏ƀ;esᆗᆜ㒆etĀ;eᆗ㒍ñᆝƀ;esᆨᆭ㒖etĀ;eᆨ㒝ñᆮƀ;afᅻ㒦ְrť㒫ֱ»ᅼaròᅈȀcemt㒹㒾㓂㓅r;쀀𝓈tmîñiì㐕aræᆾĀar㓎㓕rĀ;f㓔ឿ昆Āan㓚㓭ightĀep㓣㓪psiloîỠhé⺯s»⡒ʀbcmnp㓻㕞ሉ㖋㖎Ҁ;Edemnprs㔎㔏㔑㔕㔞㔣㔬㔱㔶抂;櫅ot;檽Ā;dᇚ㔚ot;櫃ult;櫁ĀEe㔨㔪;櫋;把lus;檿arr;楹ƀeiu㔽㕒㕕tƀ;en㔎㕅㕋qĀ;qᇚ㔏eqĀ;q㔫㔨m;櫇Ābp㕚㕜;櫕;櫓c̀;acensᇭ㕬㕲㕹㕻㌦pproø㋺urlyeñᇾñᇳƀaes㖂㖈㌛pproø㌚qñ㌗g;晪ڀ123;Edehlmnps㖩㖬㖯ሜ㖲㖴㗀㗉㗕㗚㗟㗨㗭耻¹䂹耻²䂲耻³䂳;櫆Āos㖹㖼t;檾ub;櫘Ā;dሢ㗅ot;櫄sĀou㗏㗒l;柉b;櫗arr;楻ult;櫂ĀEe㗤㗦;櫌;抋lus;櫀ƀeiu㗴㘉㘌tƀ;enሜ㗼㘂qĀ;qሢ㖲eqĀ;q㗧㗤m;櫈Ābp㘑㘓;櫔;櫖ƀAan㘜㘠㘭rr;懙rĀhr㘦㘨ë∮Ā;oਫ਩war;椪lig耻ß䃟௡㙑㙝㙠ዎ㙳㙹\0㙾㛂\0\0\0\0\0㛛㜃\0㜉㝬\0\0\0㞇ɲ㙖\0\0㙛get;挖;䏄rë๟ƀaey㙦㙫㙰ron;䅥dil;䅣;䑂lrec;挕r;쀀𝔱Ȁeiko㚆㚝㚵㚼ǲ㚋\0㚑eĀ4fኄኁaƀ;sv㚘㚙㚛䎸ym;䏑Ācn㚢㚲kĀas㚨㚮pproø዁im»ኬsðኞĀas㚺㚮ð዁rn耻þ䃾Ǭ̟㛆⋧es膀×;bd㛏㛐㛘䃗Ā;aᤏ㛕r;樱;樰ƀeps㛡㛣㜀á⩍Ȁ;bcf҆㛬㛰㛴ot;挶ir;櫱Ā;o㛹㛼쀀𝕥rk;櫚á㍢rime;怴ƀaip㜏㜒㝤dåቈ΀adempst㜡㝍㝀㝑㝗㝜㝟ngleʀ;dlqr㜰㜱㜶㝀㝂斵own»ᶻeftĀ;e⠀㜾ñम;扜ightĀ;e㊪㝋ñၚot;旬inus;樺lus;樹b;槍ime;樻ezium;揢ƀcht㝲㝽㞁Āry㝷㝻;쀀𝓉;䑆cy;䑛rok;䅧Āio㞋㞎xô᝷headĀlr㞗㞠eftarro÷ࡏightarrow»ཝऀAHabcdfghlmoprstuw㟐㟓㟗㟤㟰㟼㠎㠜㠣㠴㡑㡝㡫㢩㣌㣒㣪㣶ròϭar;楣Ācr㟜㟢ute耻ú䃺òᅐrǣ㟪\0㟭y;䑞ve;䅭Āiy㟵㟺rc耻û䃻;䑃ƀabh㠃㠆㠋ròᎭlac;䅱aòᏃĀir㠓㠘sht;楾;쀀𝔲rave耻ù䃹š㠧㠱rĀlr㠬㠮»ॗ»ႃlk;斀Āct㠹㡍ɯ㠿\0\0㡊rnĀ;e㡅㡆挜r»㡆op;挏ri;旸Āal㡖㡚cr;䅫肻¨͉Āgp㡢㡦on;䅳f;쀀𝕦̀adhlsuᅋ㡸㡽፲㢑㢠ownáᎳarpoonĀlr㢈㢌efô㠭ighô㠯iƀ;hl㢙㢚㢜䏅»ᏺon»㢚parrows;懈ƀcit㢰㣄㣈ɯ㢶\0\0㣁rnĀ;e㢼㢽挝r»㢽op;挎ng;䅯ri;旹cr;쀀𝓊ƀdir㣙㣝㣢ot;拰lde;䅩iĀ;f㜰㣨»᠓Āam㣯㣲rò㢨l耻ü䃼angle;榧ހABDacdeflnoprsz㤜㤟㤩㤭㦵㦸㦽㧟㧤㧨㧳㧹㧽㨁㨠ròϷarĀ;v㤦㤧櫨;櫩asèϡĀnr㤲㤷grt;榜΀eknprst㓣㥆㥋㥒㥝㥤㦖appá␕othinçẖƀhir㓫⻈㥙opô⾵Ā;hᎷ㥢ïㆍĀiu㥩㥭gmá㎳Ābp㥲㦄setneqĀ;q㥽㦀쀀⊊︀;쀀⫋︀setneqĀ;q㦏㦒쀀⊋︀;쀀⫌︀Āhr㦛㦟etá㚜iangleĀlr㦪㦯eft»थight»ၑy;䐲ash»ံƀelr㧄㧒㧗ƀ;beⷪ㧋㧏ar;抻q;扚lip;拮Ābt㧜ᑨaòᑩr;쀀𝔳tré㦮suĀbp㧯㧱»ജ»൙pf;쀀𝕧roð໻tré㦴Ācu㨆㨋r;쀀𝓋Ābp㨐㨘nĀEe㦀㨖»㥾nĀEe㦒㨞»㦐igzag;榚΀cefoprs㨶㨻㩖㩛㩔㩡㩪irc;䅵Ādi㩀㩑Ābg㩅㩉ar;機eĀ;qᗺ㩏;扙erp;愘r;쀀𝔴pf;쀀𝕨Ā;eᑹ㩦atèᑹcr;쀀𝓌ૣណ㪇\0㪋\0㪐㪛\0\0㪝㪨㪫㪯\0\0㫃㫎\0㫘ៜ៟tré៑r;쀀𝔵ĀAa㪔㪗ròσrò৶;䎾ĀAa㪡㪤ròθrò৫að✓is;拻ƀdptឤ㪵㪾Āfl㪺ឩ;쀀𝕩imåឲĀAa㫇㫊ròώròਁĀcq㫒ីr;쀀𝓍Āpt៖㫜ré។Ѐacefiosu㫰㫽㬈㬌㬑㬕㬛㬡cĀuy㫶㫻te耻ý䃽;䑏Āiy㬂㬆rc;䅷;䑋n耻¥䂥r;쀀𝔶cy;䑗pf;쀀𝕪cr;쀀𝓎Ācm㬦㬩y;䑎l耻ÿ䃿Ԁacdefhiosw㭂㭈㭔㭘㭤㭩㭭㭴㭺㮀cute;䅺Āay㭍㭒ron;䅾;䐷ot;䅼Āet㭝㭡træᕟa;䎶r;쀀𝔷cy;䐶grarr;懝pf;쀀𝕫cr;쀀𝓏Ājn㮅㮇;怍j;怌'.split("").map((function(c) {
  return c.charCodeAt(0);
})));

var decodeDataXml = {};

Object.defineProperty(decodeDataXml, "__esModule", {
  value: !0
}), decodeDataXml.default = new Uint16Array("Ȁaglq\tɭ\0\0p;䀦os;䀧t;䀾t;䀼uot;䀢".split("").map((function(c) {
  return c.charCodeAt(0);
})));

var decode_codepoint = {};

!function(exports) {
  var _a;
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.replaceCodePoint = exports.fromCodePoint = void 0;
  var decodeMap = new Map([ [ 0, 65533 ], [ 128, 8364 ], [ 130, 8218 ], [ 131, 402 ], [ 132, 8222 ], [ 133, 8230 ], [ 134, 8224 ], [ 135, 8225 ], [ 136, 710 ], [ 137, 8240 ], [ 138, 352 ], [ 139, 8249 ], [ 140, 338 ], [ 142, 381 ], [ 145, 8216 ], [ 146, 8217 ], [ 147, 8220 ], [ 148, 8221 ], [ 149, 8226 ], [ 150, 8211 ], [ 151, 8212 ], [ 152, 732 ], [ 153, 8482 ], [ 154, 353 ], [ 155, 8250 ], [ 156, 339 ], [ 158, 382 ], [ 159, 376 ] ]);
  function replaceCodePoint(codePoint) {
    var _a;
    return codePoint >= 0xd800 && codePoint <= 0xdfff || codePoint > 0x10ffff ? 0xfffd : null !== (_a = decodeMap.get(codePoint)) && void 0 !== _a ? _a : codePoint;
  }
  exports.fromCodePoint = null !== (_a = String.fromCodePoint) && void 0 !== _a ? _a : function(codePoint) {
    var output = "";
    return codePoint > 0xffff && (codePoint -= 0x10000, output += String.fromCharCode(codePoint >>> 10 & 0x3ff | 0xd800), 
    codePoint = 0xdc00 | 0x3ff & codePoint), output += String.fromCharCode(codePoint);
  }, exports.replaceCodePoint = replaceCodePoint, exports.default = function(codePoint) {
    return (0, exports.fromCodePoint)(replaceCodePoint(codePoint));
  };
}(decode_codepoint), function(exports) {
  var __createBinding = commonjsGlobal && commonjsGlobal.__createBinding || (Object.create ? function(o, m, k, k2) {
    void 0 === k2 && (k2 = k);
    var desc = Object.getOwnPropertyDescriptor(m, k);
    desc && !("get" in desc ? !m.__esModule : desc.writable || desc.configurable) || (desc = {
      enumerable: !0,
      get: function() {
        return m[k];
      }
    }), Object.defineProperty(o, k2, desc);
  } : function(o, m, k, k2) {
    void 0 === k2 && (k2 = k), o[k2] = m[k];
  }), __setModuleDefault = commonjsGlobal && commonjsGlobal.__setModuleDefault || (Object.create ? function(o, v) {
    Object.defineProperty(o, "default", {
      enumerable: !0,
      value: v
    });
  } : function(o, v) {
    o.default = v;
  }), __importStar = commonjsGlobal && commonjsGlobal.__importStar || function(mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (null != mod) for (var k in mod) "default" !== k && Object.prototype.hasOwnProperty.call(mod, k) && __createBinding(result, mod, k);
    return __setModuleDefault(result, mod), result;
  }, __importDefault = commonjsGlobal && commonjsGlobal.__importDefault || function(mod) {
    return mod && mod.__esModule ? mod : {
      "default": mod
    };
  };
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.decodeXML = exports.decodeHTMLStrict = exports.decodeHTMLAttribute = exports.decodeHTML = exports.determineBranch = exports.EntityDecoder = exports.DecodingMode = exports.BinTrieFlags = exports.fromCodePoint = exports.replaceCodePoint = exports.decodeCodePoint = exports.xmlDecodeTree = exports.htmlDecodeTree = void 0;
  var decode_data_html_js_1 = __importDefault(decodeDataHtml);
  exports.htmlDecodeTree = decode_data_html_js_1.default;
  var decode_data_xml_js_1 = __importDefault(decodeDataXml);
  exports.xmlDecodeTree = decode_data_xml_js_1.default;
  var decode_codepoint_js_1 = __importStar(decode_codepoint);
  exports.decodeCodePoint = decode_codepoint_js_1.default;
  var CharCodes, decode_codepoint_js_2 = decode_codepoint;
  Object.defineProperty(exports, "replaceCodePoint", {
    enumerable: !0,
    get: function() {
      return decode_codepoint_js_2.replaceCodePoint;
    }
  }), Object.defineProperty(exports, "fromCodePoint", {
    enumerable: !0,
    get: function() {
      return decode_codepoint_js_2.fromCodePoint;
    }
  }), function(CharCodes) {
    CharCodes[CharCodes.NUM = 35] = "NUM", CharCodes[CharCodes.SEMI = 59] = "SEMI", 
    CharCodes[CharCodes.EQUALS = 61] = "EQUALS", CharCodes[CharCodes.ZERO = 48] = "ZERO", 
    CharCodes[CharCodes.NINE = 57] = "NINE", CharCodes[CharCodes.LOWER_A = 97] = "LOWER_A", 
    CharCodes[CharCodes.LOWER_F = 102] = "LOWER_F", CharCodes[CharCodes.LOWER_X = 120] = "LOWER_X", 
    CharCodes[CharCodes.LOWER_Z = 122] = "LOWER_Z", CharCodes[CharCodes.UPPER_A = 65] = "UPPER_A", 
    CharCodes[CharCodes.UPPER_F = 70] = "UPPER_F", CharCodes[CharCodes.UPPER_Z = 90] = "UPPER_Z";
  }(CharCodes || (CharCodes = {}));
  var BinTrieFlags, EntityDecoderState, DecodingMode;
  function isNumber(code) {
    return code >= CharCodes.ZERO && code <= CharCodes.NINE;
  }
  function isHexadecimalCharacter(code) {
    return code >= CharCodes.UPPER_A && code <= CharCodes.UPPER_F || code >= CharCodes.LOWER_A && code <= CharCodes.LOWER_F;
  }
  function isEntityInAttributeInvalidEnd(code) {
    return code === CharCodes.EQUALS || function(code) {
      return code >= CharCodes.UPPER_A && code <= CharCodes.UPPER_Z || code >= CharCodes.LOWER_A && code <= CharCodes.LOWER_Z || isNumber(code);
    }(code);
  }
  !function(BinTrieFlags) {
    BinTrieFlags[BinTrieFlags.VALUE_LENGTH = 49152] = "VALUE_LENGTH", BinTrieFlags[BinTrieFlags.BRANCH_LENGTH = 16256] = "BRANCH_LENGTH", 
    BinTrieFlags[BinTrieFlags.JUMP_TABLE = 127] = "JUMP_TABLE";
  }(BinTrieFlags = exports.BinTrieFlags || (exports.BinTrieFlags = {})), function(EntityDecoderState) {
    EntityDecoderState[EntityDecoderState.EntityStart = 0] = "EntityStart", EntityDecoderState[EntityDecoderState.NumericStart = 1] = "NumericStart", 
    EntityDecoderState[EntityDecoderState.NumericDecimal = 2] = "NumericDecimal", EntityDecoderState[EntityDecoderState.NumericHex = 3] = "NumericHex", 
    EntityDecoderState[EntityDecoderState.NamedEntity = 4] = "NamedEntity";
  }(EntityDecoderState || (EntityDecoderState = {})), function(DecodingMode) {
    DecodingMode[DecodingMode.Legacy = 0] = "Legacy", DecodingMode[DecodingMode.Strict = 1] = "Strict", 
    DecodingMode[DecodingMode.Attribute = 2] = "Attribute";
  }(DecodingMode = exports.DecodingMode || (exports.DecodingMode = {}));
  var EntityDecoder = function() {
    function EntityDecoder(decodeTree, emitCodePoint, errors) {
      this.decodeTree = decodeTree, this.emitCodePoint = emitCodePoint, this.errors = errors, 
      this.state = EntityDecoderState.EntityStart, this.consumed = 1, this.result = 0, 
      this.treeIndex = 0, this.excess = 1, this.decodeMode = DecodingMode.Strict;
    }
    return EntityDecoder.prototype.startEntity = function(decodeMode) {
      this.decodeMode = decodeMode, this.state = EntityDecoderState.EntityStart, this.result = 0, 
      this.treeIndex = 0, this.excess = 1, this.consumed = 1;
    }, EntityDecoder.prototype.write = function(str, offset) {
      switch (this.state) {
       case EntityDecoderState.EntityStart:
        return str.charCodeAt(offset) === CharCodes.NUM ? (this.state = EntityDecoderState.NumericStart, 
        this.consumed += 1, this.stateNumericStart(str, offset + 1)) : (this.state = EntityDecoderState.NamedEntity, 
        this.stateNamedEntity(str, offset));

       case EntityDecoderState.NumericStart:
        return this.stateNumericStart(str, offset);

       case EntityDecoderState.NumericDecimal:
        return this.stateNumericDecimal(str, offset);

       case EntityDecoderState.NumericHex:
        return this.stateNumericHex(str, offset);

       case EntityDecoderState.NamedEntity:
        return this.stateNamedEntity(str, offset);
      }
    }, EntityDecoder.prototype.stateNumericStart = function(str, offset) {
      return offset >= str.length ? -1 : (32 | str.charCodeAt(offset)) === CharCodes.LOWER_X ? (this.state = EntityDecoderState.NumericHex, 
      this.consumed += 1, this.stateNumericHex(str, offset + 1)) : (this.state = EntityDecoderState.NumericDecimal, 
      this.stateNumericDecimal(str, offset));
    }, EntityDecoder.prototype.addToNumericResult = function(str, start, end, base) {
      if (start !== end) {
        var digitCount = end - start;
        this.result = this.result * Math.pow(base, digitCount) + parseInt(str.substr(start, digitCount), base), 
        this.consumed += digitCount;
      }
    }, EntityDecoder.prototype.stateNumericHex = function(str, offset) {
      for (var startIdx = offset; offset < str.length; ) {
        var char = str.charCodeAt(offset);
        if (!isNumber(char) && !isHexadecimalCharacter(char)) return this.addToNumericResult(str, startIdx, offset, 16), 
        this.emitNumericEntity(char, 3);
        offset += 1;
      }
      return this.addToNumericResult(str, startIdx, offset, 16), -1;
    }, EntityDecoder.prototype.stateNumericDecimal = function(str, offset) {
      for (var startIdx = offset; offset < str.length; ) {
        var char = str.charCodeAt(offset);
        if (!isNumber(char)) return this.addToNumericResult(str, startIdx, offset, 10), 
        this.emitNumericEntity(char, 2);
        offset += 1;
      }
      return this.addToNumericResult(str, startIdx, offset, 10), -1;
    }, EntityDecoder.prototype.emitNumericEntity = function(lastCp, expectedLength) {
      var _a;
      if (this.consumed <= expectedLength) return null === (_a = this.errors) || void 0 === _a || _a.absenceOfDigitsInNumericCharacterReference(this.consumed), 
      0;
      if (lastCp === CharCodes.SEMI) this.consumed += 1; else if (this.decodeMode === DecodingMode.Strict) return 0;
      return this.emitCodePoint((0, decode_codepoint_js_1.replaceCodePoint)(this.result), this.consumed), 
      this.errors && (lastCp !== CharCodes.SEMI && this.errors.missingSemicolonAfterCharacterReference(), 
      this.errors.validateNumericCharacterReference(this.result)), this.consumed;
    }, EntityDecoder.prototype.stateNamedEntity = function(str, offset) {
      for (var decodeTree = this.decodeTree, current = decodeTree[this.treeIndex], valueLength = (current & BinTrieFlags.VALUE_LENGTH) >> 14; offset < str.length; offset++, 
      this.excess++) {
        var char = str.charCodeAt(offset);
        if (this.treeIndex = determineBranch(decodeTree, current, this.treeIndex + Math.max(1, valueLength), char), 
        this.treeIndex < 0) return 0 === this.result || this.decodeMode === DecodingMode.Attribute && (0 === valueLength || isEntityInAttributeInvalidEnd(char)) ? 0 : this.emitNotTerminatedNamedEntity();
        if (0 !== (valueLength = ((current = decodeTree[this.treeIndex]) & BinTrieFlags.VALUE_LENGTH) >> 14)) {
          if (char === CharCodes.SEMI) return this.emitNamedEntityData(this.treeIndex, valueLength, this.consumed + this.excess);
          this.decodeMode !== DecodingMode.Strict && (this.result = this.treeIndex, this.consumed += this.excess, 
          this.excess = 0);
        }
      }
      return -1;
    }, EntityDecoder.prototype.emitNotTerminatedNamedEntity = function() {
      var _a, result = this.result, valueLength = (this.decodeTree[result] & BinTrieFlags.VALUE_LENGTH) >> 14;
      return this.emitNamedEntityData(result, valueLength, this.consumed), null === (_a = this.errors) || void 0 === _a || _a.missingSemicolonAfterCharacterReference(), 
      this.consumed;
    }, EntityDecoder.prototype.emitNamedEntityData = function(result, valueLength, consumed) {
      var decodeTree = this.decodeTree;
      return this.emitCodePoint(1 === valueLength ? decodeTree[result] & ~BinTrieFlags.VALUE_LENGTH : decodeTree[result + 1], consumed), 
      3 === valueLength && this.emitCodePoint(decodeTree[result + 2], consumed), consumed;
    }, EntityDecoder.prototype.end = function() {
      var _a;
      switch (this.state) {
       case EntityDecoderState.NamedEntity:
        return 0 === this.result || this.decodeMode === DecodingMode.Attribute && this.result !== this.treeIndex ? 0 : this.emitNotTerminatedNamedEntity();

       case EntityDecoderState.NumericDecimal:
        return this.emitNumericEntity(0, 2);

       case EntityDecoderState.NumericHex:
        return this.emitNumericEntity(0, 3);

       case EntityDecoderState.NumericStart:
        return null === (_a = this.errors) || void 0 === _a || _a.absenceOfDigitsInNumericCharacterReference(this.consumed), 
        0;

       case EntityDecoderState.EntityStart:
        return 0;
      }
    }, EntityDecoder;
  }();
  function getDecoder(decodeTree) {
    var ret = "", decoder = new EntityDecoder(decodeTree, (function(str) {
      return ret += (0, decode_codepoint_js_1.fromCodePoint)(str);
    }));
    return function(str, decodeMode) {
      for (var lastIndex = 0, offset = 0; (offset = str.indexOf("&", offset)) >= 0; ) {
        ret += str.slice(lastIndex, offset), decoder.startEntity(decodeMode);
        var len = decoder.write(str, offset + 1);
        if (len < 0) {
          lastIndex = offset + decoder.end();
          break;
        }
        lastIndex = offset + len, offset = 0 === len ? lastIndex + 1 : lastIndex;
      }
      var result = ret + str.slice(lastIndex);
      return ret = "", result;
    };
  }
  function determineBranch(decodeTree, current, nodeIdx, char) {
    var branchCount = (current & BinTrieFlags.BRANCH_LENGTH) >> 7, jumpOffset = current & BinTrieFlags.JUMP_TABLE;
    if (0 === branchCount) return 0 !== jumpOffset && char === jumpOffset ? nodeIdx : -1;
    if (jumpOffset) {
      var value = char - jumpOffset;
      return value < 0 || value >= branchCount ? -1 : decodeTree[nodeIdx + value] - 1;
    }
    for (var lo = nodeIdx, hi = lo + branchCount - 1; lo <= hi; ) {
      var mid = lo + hi >>> 1, midVal = decodeTree[mid];
      if (midVal < char) lo = mid + 1; else {
        if (!(midVal > char)) return decodeTree[mid + branchCount];
        hi = mid - 1;
      }
    }
    return -1;
  }
  exports.EntityDecoder = EntityDecoder, exports.determineBranch = determineBranch;
  var htmlDecoder = getDecoder(decode_data_html_js_1.default), xmlDecoder = getDecoder(decode_data_xml_js_1.default);
  exports.decodeHTML = function(str, mode) {
    return void 0 === mode && (mode = DecodingMode.Legacy), htmlDecoder(str, mode);
  }, exports.decodeHTMLAttribute = function(str) {
    return htmlDecoder(str, DecodingMode.Attribute);
  }, exports.decodeHTMLStrict = function(str) {
    return htmlDecoder(str, DecodingMode.Strict);
  }, exports.decodeXML = function(str) {
    return xmlDecoder(str, DecodingMode.Strict);
  };
}(decode$6);

var encode$6 = {}, encodeHtml = {};

function restoreDiff(arr) {
  for (var i = 1; i < arr.length; i++) arr[i][0] += arr[i - 1][0] + 1;
  return arr;
}

Object.defineProperty(encodeHtml, "__esModule", {
  value: !0
}), encodeHtml.default = new Map(restoreDiff([ [ 9, "&Tab;" ], [ 0, "&NewLine;" ], [ 22, "&excl;" ], [ 0, "&quot;" ], [ 0, "&num;" ], [ 0, "&dollar;" ], [ 0, "&percnt;" ], [ 0, "&amp;" ], [ 0, "&apos;" ], [ 0, "&lpar;" ], [ 0, "&rpar;" ], [ 0, "&ast;" ], [ 0, "&plus;" ], [ 0, "&comma;" ], [ 1, "&period;" ], [ 0, "&sol;" ], [ 10, "&colon;" ], [ 0, "&semi;" ], [ 0, {
  v: "&lt;",
  n: 8402,
  o: "&nvlt;"
} ], [ 0, {
  v: "&equals;",
  n: 8421,
  o: "&bne;"
} ], [ 0, {
  v: "&gt;",
  n: 8402,
  o: "&nvgt;"
} ], [ 0, "&quest;" ], [ 0, "&commat;" ], [ 26, "&lbrack;" ], [ 0, "&bsol;" ], [ 0, "&rbrack;" ], [ 0, "&Hat;" ], [ 0, "&lowbar;" ], [ 0, "&DiacriticalGrave;" ], [ 5, {
  n: 106,
  o: "&fjlig;"
} ], [ 20, "&lbrace;" ], [ 0, "&verbar;" ], [ 0, "&rbrace;" ], [ 34, "&nbsp;" ], [ 0, "&iexcl;" ], [ 0, "&cent;" ], [ 0, "&pound;" ], [ 0, "&curren;" ], [ 0, "&yen;" ], [ 0, "&brvbar;" ], [ 0, "&sect;" ], [ 0, "&die;" ], [ 0, "&copy;" ], [ 0, "&ordf;" ], [ 0, "&laquo;" ], [ 0, "&not;" ], [ 0, "&shy;" ], [ 0, "&circledR;" ], [ 0, "&macr;" ], [ 0, "&deg;" ], [ 0, "&PlusMinus;" ], [ 0, "&sup2;" ], [ 0, "&sup3;" ], [ 0, "&acute;" ], [ 0, "&micro;" ], [ 0, "&para;" ], [ 0, "&centerdot;" ], [ 0, "&cedil;" ], [ 0, "&sup1;" ], [ 0, "&ordm;" ], [ 0, "&raquo;" ], [ 0, "&frac14;" ], [ 0, "&frac12;" ], [ 0, "&frac34;" ], [ 0, "&iquest;" ], [ 0, "&Agrave;" ], [ 0, "&Aacute;" ], [ 0, "&Acirc;" ], [ 0, "&Atilde;" ], [ 0, "&Auml;" ], [ 0, "&angst;" ], [ 0, "&AElig;" ], [ 0, "&Ccedil;" ], [ 0, "&Egrave;" ], [ 0, "&Eacute;" ], [ 0, "&Ecirc;" ], [ 0, "&Euml;" ], [ 0, "&Igrave;" ], [ 0, "&Iacute;" ], [ 0, "&Icirc;" ], [ 0, "&Iuml;" ], [ 0, "&ETH;" ], [ 0, "&Ntilde;" ], [ 0, "&Ograve;" ], [ 0, "&Oacute;" ], [ 0, "&Ocirc;" ], [ 0, "&Otilde;" ], [ 0, "&Ouml;" ], [ 0, "&times;" ], [ 0, "&Oslash;" ], [ 0, "&Ugrave;" ], [ 0, "&Uacute;" ], [ 0, "&Ucirc;" ], [ 0, "&Uuml;" ], [ 0, "&Yacute;" ], [ 0, "&THORN;" ], [ 0, "&szlig;" ], [ 0, "&agrave;" ], [ 0, "&aacute;" ], [ 0, "&acirc;" ], [ 0, "&atilde;" ], [ 0, "&auml;" ], [ 0, "&aring;" ], [ 0, "&aelig;" ], [ 0, "&ccedil;" ], [ 0, "&egrave;" ], [ 0, "&eacute;" ], [ 0, "&ecirc;" ], [ 0, "&euml;" ], [ 0, "&igrave;" ], [ 0, "&iacute;" ], [ 0, "&icirc;" ], [ 0, "&iuml;" ], [ 0, "&eth;" ], [ 0, "&ntilde;" ], [ 0, "&ograve;" ], [ 0, "&oacute;" ], [ 0, "&ocirc;" ], [ 0, "&otilde;" ], [ 0, "&ouml;" ], [ 0, "&div;" ], [ 0, "&oslash;" ], [ 0, "&ugrave;" ], [ 0, "&uacute;" ], [ 0, "&ucirc;" ], [ 0, "&uuml;" ], [ 0, "&yacute;" ], [ 0, "&thorn;" ], [ 0, "&yuml;" ], [ 0, "&Amacr;" ], [ 0, "&amacr;" ], [ 0, "&Abreve;" ], [ 0, "&abreve;" ], [ 0, "&Aogon;" ], [ 0, "&aogon;" ], [ 0, "&Cacute;" ], [ 0, "&cacute;" ], [ 0, "&Ccirc;" ], [ 0, "&ccirc;" ], [ 0, "&Cdot;" ], [ 0, "&cdot;" ], [ 0, "&Ccaron;" ], [ 0, "&ccaron;" ], [ 0, "&Dcaron;" ], [ 0, "&dcaron;" ], [ 0, "&Dstrok;" ], [ 0, "&dstrok;" ], [ 0, "&Emacr;" ], [ 0, "&emacr;" ], [ 2, "&Edot;" ], [ 0, "&edot;" ], [ 0, "&Eogon;" ], [ 0, "&eogon;" ], [ 0, "&Ecaron;" ], [ 0, "&ecaron;" ], [ 0, "&Gcirc;" ], [ 0, "&gcirc;" ], [ 0, "&Gbreve;" ], [ 0, "&gbreve;" ], [ 0, "&Gdot;" ], [ 0, "&gdot;" ], [ 0, "&Gcedil;" ], [ 1, "&Hcirc;" ], [ 0, "&hcirc;" ], [ 0, "&Hstrok;" ], [ 0, "&hstrok;" ], [ 0, "&Itilde;" ], [ 0, "&itilde;" ], [ 0, "&Imacr;" ], [ 0, "&imacr;" ], [ 2, "&Iogon;" ], [ 0, "&iogon;" ], [ 0, "&Idot;" ], [ 0, "&imath;" ], [ 0, "&IJlig;" ], [ 0, "&ijlig;" ], [ 0, "&Jcirc;" ], [ 0, "&jcirc;" ], [ 0, "&Kcedil;" ], [ 0, "&kcedil;" ], [ 0, "&kgreen;" ], [ 0, "&Lacute;" ], [ 0, "&lacute;" ], [ 0, "&Lcedil;" ], [ 0, "&lcedil;" ], [ 0, "&Lcaron;" ], [ 0, "&lcaron;" ], [ 0, "&Lmidot;" ], [ 0, "&lmidot;" ], [ 0, "&Lstrok;" ], [ 0, "&lstrok;" ], [ 0, "&Nacute;" ], [ 0, "&nacute;" ], [ 0, "&Ncedil;" ], [ 0, "&ncedil;" ], [ 0, "&Ncaron;" ], [ 0, "&ncaron;" ], [ 0, "&napos;" ], [ 0, "&ENG;" ], [ 0, "&eng;" ], [ 0, "&Omacr;" ], [ 0, "&omacr;" ], [ 2, "&Odblac;" ], [ 0, "&odblac;" ], [ 0, "&OElig;" ], [ 0, "&oelig;" ], [ 0, "&Racute;" ], [ 0, "&racute;" ], [ 0, "&Rcedil;" ], [ 0, "&rcedil;" ], [ 0, "&Rcaron;" ], [ 0, "&rcaron;" ], [ 0, "&Sacute;" ], [ 0, "&sacute;" ], [ 0, "&Scirc;" ], [ 0, "&scirc;" ], [ 0, "&Scedil;" ], [ 0, "&scedil;" ], [ 0, "&Scaron;" ], [ 0, "&scaron;" ], [ 0, "&Tcedil;" ], [ 0, "&tcedil;" ], [ 0, "&Tcaron;" ], [ 0, "&tcaron;" ], [ 0, "&Tstrok;" ], [ 0, "&tstrok;" ], [ 0, "&Utilde;" ], [ 0, "&utilde;" ], [ 0, "&Umacr;" ], [ 0, "&umacr;" ], [ 0, "&Ubreve;" ], [ 0, "&ubreve;" ], [ 0, "&Uring;" ], [ 0, "&uring;" ], [ 0, "&Udblac;" ], [ 0, "&udblac;" ], [ 0, "&Uogon;" ], [ 0, "&uogon;" ], [ 0, "&Wcirc;" ], [ 0, "&wcirc;" ], [ 0, "&Ycirc;" ], [ 0, "&ycirc;" ], [ 0, "&Yuml;" ], [ 0, "&Zacute;" ], [ 0, "&zacute;" ], [ 0, "&Zdot;" ], [ 0, "&zdot;" ], [ 0, "&Zcaron;" ], [ 0, "&zcaron;" ], [ 19, "&fnof;" ], [ 34, "&imped;" ], [ 63, "&gacute;" ], [ 65, "&jmath;" ], [ 142, "&circ;" ], [ 0, "&caron;" ], [ 16, "&breve;" ], [ 0, "&DiacriticalDot;" ], [ 0, "&ring;" ], [ 0, "&ogon;" ], [ 0, "&DiacriticalTilde;" ], [ 0, "&dblac;" ], [ 51, "&DownBreve;" ], [ 127, "&Alpha;" ], [ 0, "&Beta;" ], [ 0, "&Gamma;" ], [ 0, "&Delta;" ], [ 0, "&Epsilon;" ], [ 0, "&Zeta;" ], [ 0, "&Eta;" ], [ 0, "&Theta;" ], [ 0, "&Iota;" ], [ 0, "&Kappa;" ], [ 0, "&Lambda;" ], [ 0, "&Mu;" ], [ 0, "&Nu;" ], [ 0, "&Xi;" ], [ 0, "&Omicron;" ], [ 0, "&Pi;" ], [ 0, "&Rho;" ], [ 1, "&Sigma;" ], [ 0, "&Tau;" ], [ 0, "&Upsilon;" ], [ 0, "&Phi;" ], [ 0, "&Chi;" ], [ 0, "&Psi;" ], [ 0, "&ohm;" ], [ 7, "&alpha;" ], [ 0, "&beta;" ], [ 0, "&gamma;" ], [ 0, "&delta;" ], [ 0, "&epsi;" ], [ 0, "&zeta;" ], [ 0, "&eta;" ], [ 0, "&theta;" ], [ 0, "&iota;" ], [ 0, "&kappa;" ], [ 0, "&lambda;" ], [ 0, "&mu;" ], [ 0, "&nu;" ], [ 0, "&xi;" ], [ 0, "&omicron;" ], [ 0, "&pi;" ], [ 0, "&rho;" ], [ 0, "&sigmaf;" ], [ 0, "&sigma;" ], [ 0, "&tau;" ], [ 0, "&upsi;" ], [ 0, "&phi;" ], [ 0, "&chi;" ], [ 0, "&psi;" ], [ 0, "&omega;" ], [ 7, "&thetasym;" ], [ 0, "&Upsi;" ], [ 2, "&phiv;" ], [ 0, "&piv;" ], [ 5, "&Gammad;" ], [ 0, "&digamma;" ], [ 18, "&kappav;" ], [ 0, "&rhov;" ], [ 3, "&epsiv;" ], [ 0, "&backepsilon;" ], [ 10, "&IOcy;" ], [ 0, "&DJcy;" ], [ 0, "&GJcy;" ], [ 0, "&Jukcy;" ], [ 0, "&DScy;" ], [ 0, "&Iukcy;" ], [ 0, "&YIcy;" ], [ 0, "&Jsercy;" ], [ 0, "&LJcy;" ], [ 0, "&NJcy;" ], [ 0, "&TSHcy;" ], [ 0, "&KJcy;" ], [ 1, "&Ubrcy;" ], [ 0, "&DZcy;" ], [ 0, "&Acy;" ], [ 0, "&Bcy;" ], [ 0, "&Vcy;" ], [ 0, "&Gcy;" ], [ 0, "&Dcy;" ], [ 0, "&IEcy;" ], [ 0, "&ZHcy;" ], [ 0, "&Zcy;" ], [ 0, "&Icy;" ], [ 0, "&Jcy;" ], [ 0, "&Kcy;" ], [ 0, "&Lcy;" ], [ 0, "&Mcy;" ], [ 0, "&Ncy;" ], [ 0, "&Ocy;" ], [ 0, "&Pcy;" ], [ 0, "&Rcy;" ], [ 0, "&Scy;" ], [ 0, "&Tcy;" ], [ 0, "&Ucy;" ], [ 0, "&Fcy;" ], [ 0, "&KHcy;" ], [ 0, "&TScy;" ], [ 0, "&CHcy;" ], [ 0, "&SHcy;" ], [ 0, "&SHCHcy;" ], [ 0, "&HARDcy;" ], [ 0, "&Ycy;" ], [ 0, "&SOFTcy;" ], [ 0, "&Ecy;" ], [ 0, "&YUcy;" ], [ 0, "&YAcy;" ], [ 0, "&acy;" ], [ 0, "&bcy;" ], [ 0, "&vcy;" ], [ 0, "&gcy;" ], [ 0, "&dcy;" ], [ 0, "&iecy;" ], [ 0, "&zhcy;" ], [ 0, "&zcy;" ], [ 0, "&icy;" ], [ 0, "&jcy;" ], [ 0, "&kcy;" ], [ 0, "&lcy;" ], [ 0, "&mcy;" ], [ 0, "&ncy;" ], [ 0, "&ocy;" ], [ 0, "&pcy;" ], [ 0, "&rcy;" ], [ 0, "&scy;" ], [ 0, "&tcy;" ], [ 0, "&ucy;" ], [ 0, "&fcy;" ], [ 0, "&khcy;" ], [ 0, "&tscy;" ], [ 0, "&chcy;" ], [ 0, "&shcy;" ], [ 0, "&shchcy;" ], [ 0, "&hardcy;" ], [ 0, "&ycy;" ], [ 0, "&softcy;" ], [ 0, "&ecy;" ], [ 0, "&yucy;" ], [ 0, "&yacy;" ], [ 1, "&iocy;" ], [ 0, "&djcy;" ], [ 0, "&gjcy;" ], [ 0, "&jukcy;" ], [ 0, "&dscy;" ], [ 0, "&iukcy;" ], [ 0, "&yicy;" ], [ 0, "&jsercy;" ], [ 0, "&ljcy;" ], [ 0, "&njcy;" ], [ 0, "&tshcy;" ], [ 0, "&kjcy;" ], [ 1, "&ubrcy;" ], [ 0, "&dzcy;" ], [ 7074, "&ensp;" ], [ 0, "&emsp;" ], [ 0, "&emsp13;" ], [ 0, "&emsp14;" ], [ 1, "&numsp;" ], [ 0, "&puncsp;" ], [ 0, "&ThinSpace;" ], [ 0, "&hairsp;" ], [ 0, "&NegativeMediumSpace;" ], [ 0, "&zwnj;" ], [ 0, "&zwj;" ], [ 0, "&lrm;" ], [ 0, "&rlm;" ], [ 0, "&dash;" ], [ 2, "&ndash;" ], [ 0, "&mdash;" ], [ 0, "&horbar;" ], [ 0, "&Verbar;" ], [ 1, "&lsquo;" ], [ 0, "&CloseCurlyQuote;" ], [ 0, "&lsquor;" ], [ 1, "&ldquo;" ], [ 0, "&CloseCurlyDoubleQuote;" ], [ 0, "&bdquo;" ], [ 1, "&dagger;" ], [ 0, "&Dagger;" ], [ 0, "&bull;" ], [ 2, "&nldr;" ], [ 0, "&hellip;" ], [ 9, "&permil;" ], [ 0, "&pertenk;" ], [ 0, "&prime;" ], [ 0, "&Prime;" ], [ 0, "&tprime;" ], [ 0, "&backprime;" ], [ 3, "&lsaquo;" ], [ 0, "&rsaquo;" ], [ 3, "&oline;" ], [ 2, "&caret;" ], [ 1, "&hybull;" ], [ 0, "&frasl;" ], [ 10, "&bsemi;" ], [ 7, "&qprime;" ], [ 7, {
  v: "&MediumSpace;",
  n: 8202,
  o: "&ThickSpace;"
} ], [ 0, "&NoBreak;" ], [ 0, "&af;" ], [ 0, "&InvisibleTimes;" ], [ 0, "&ic;" ], [ 72, "&euro;" ], [ 46, "&tdot;" ], [ 0, "&DotDot;" ], [ 37, "&complexes;" ], [ 2, "&incare;" ], [ 4, "&gscr;" ], [ 0, "&hamilt;" ], [ 0, "&Hfr;" ], [ 0, "&Hopf;" ], [ 0, "&planckh;" ], [ 0, "&hbar;" ], [ 0, "&imagline;" ], [ 0, "&Ifr;" ], [ 0, "&lagran;" ], [ 0, "&ell;" ], [ 1, "&naturals;" ], [ 0, "&numero;" ], [ 0, "&copysr;" ], [ 0, "&weierp;" ], [ 0, "&Popf;" ], [ 0, "&Qopf;" ], [ 0, "&realine;" ], [ 0, "&real;" ], [ 0, "&reals;" ], [ 0, "&rx;" ], [ 3, "&trade;" ], [ 1, "&integers;" ], [ 2, "&mho;" ], [ 0, "&zeetrf;" ], [ 0, "&iiota;" ], [ 2, "&bernou;" ], [ 0, "&Cayleys;" ], [ 1, "&escr;" ], [ 0, "&Escr;" ], [ 0, "&Fouriertrf;" ], [ 1, "&Mellintrf;" ], [ 0, "&order;" ], [ 0, "&alefsym;" ], [ 0, "&beth;" ], [ 0, "&gimel;" ], [ 0, "&daleth;" ], [ 12, "&CapitalDifferentialD;" ], [ 0, "&dd;" ], [ 0, "&ee;" ], [ 0, "&ii;" ], [ 10, "&frac13;" ], [ 0, "&frac23;" ], [ 0, "&frac15;" ], [ 0, "&frac25;" ], [ 0, "&frac35;" ], [ 0, "&frac45;" ], [ 0, "&frac16;" ], [ 0, "&frac56;" ], [ 0, "&frac18;" ], [ 0, "&frac38;" ], [ 0, "&frac58;" ], [ 0, "&frac78;" ], [ 49, "&larr;" ], [ 0, "&ShortUpArrow;" ], [ 0, "&rarr;" ], [ 0, "&darr;" ], [ 0, "&harr;" ], [ 0, "&updownarrow;" ], [ 0, "&nwarr;" ], [ 0, "&nearr;" ], [ 0, "&LowerRightArrow;" ], [ 0, "&LowerLeftArrow;" ], [ 0, "&nlarr;" ], [ 0, "&nrarr;" ], [ 1, {
  v: "&rarrw;",
  n: 824,
  o: "&nrarrw;"
} ], [ 0, "&Larr;" ], [ 0, "&Uarr;" ], [ 0, "&Rarr;" ], [ 0, "&Darr;" ], [ 0, "&larrtl;" ], [ 0, "&rarrtl;" ], [ 0, "&LeftTeeArrow;" ], [ 0, "&mapstoup;" ], [ 0, "&map;" ], [ 0, "&DownTeeArrow;" ], [ 1, "&hookleftarrow;" ], [ 0, "&hookrightarrow;" ], [ 0, "&larrlp;" ], [ 0, "&looparrowright;" ], [ 0, "&harrw;" ], [ 0, "&nharr;" ], [ 1, "&lsh;" ], [ 0, "&rsh;" ], [ 0, "&ldsh;" ], [ 0, "&rdsh;" ], [ 1, "&crarr;" ], [ 0, "&cularr;" ], [ 0, "&curarr;" ], [ 2, "&circlearrowleft;" ], [ 0, "&circlearrowright;" ], [ 0, "&leftharpoonup;" ], [ 0, "&DownLeftVector;" ], [ 0, "&RightUpVector;" ], [ 0, "&LeftUpVector;" ], [ 0, "&rharu;" ], [ 0, "&DownRightVector;" ], [ 0, "&dharr;" ], [ 0, "&dharl;" ], [ 0, "&RightArrowLeftArrow;" ], [ 0, "&udarr;" ], [ 0, "&LeftArrowRightArrow;" ], [ 0, "&leftleftarrows;" ], [ 0, "&upuparrows;" ], [ 0, "&rightrightarrows;" ], [ 0, "&ddarr;" ], [ 0, "&leftrightharpoons;" ], [ 0, "&Equilibrium;" ], [ 0, "&nlArr;" ], [ 0, "&nhArr;" ], [ 0, "&nrArr;" ], [ 0, "&DoubleLeftArrow;" ], [ 0, "&DoubleUpArrow;" ], [ 0, "&DoubleRightArrow;" ], [ 0, "&dArr;" ], [ 0, "&DoubleLeftRightArrow;" ], [ 0, "&DoubleUpDownArrow;" ], [ 0, "&nwArr;" ], [ 0, "&neArr;" ], [ 0, "&seArr;" ], [ 0, "&swArr;" ], [ 0, "&lAarr;" ], [ 0, "&rAarr;" ], [ 1, "&zigrarr;" ], [ 6, "&larrb;" ], [ 0, "&rarrb;" ], [ 15, "&DownArrowUpArrow;" ], [ 7, "&loarr;" ], [ 0, "&roarr;" ], [ 0, "&hoarr;" ], [ 0, "&forall;" ], [ 0, "&comp;" ], [ 0, {
  v: "&part;",
  n: 824,
  o: "&npart;"
} ], [ 0, "&exist;" ], [ 0, "&nexist;" ], [ 0, "&empty;" ], [ 1, "&Del;" ], [ 0, "&Element;" ], [ 0, "&NotElement;" ], [ 1, "&ni;" ], [ 0, "&notni;" ], [ 2, "&prod;" ], [ 0, "&coprod;" ], [ 0, "&sum;" ], [ 0, "&minus;" ], [ 0, "&MinusPlus;" ], [ 0, "&dotplus;" ], [ 1, "&Backslash;" ], [ 0, "&lowast;" ], [ 0, "&compfn;" ], [ 1, "&radic;" ], [ 2, "&prop;" ], [ 0, "&infin;" ], [ 0, "&angrt;" ], [ 0, {
  v: "&ang;",
  n: 8402,
  o: "&nang;"
} ], [ 0, "&angmsd;" ], [ 0, "&angsph;" ], [ 0, "&mid;" ], [ 0, "&nmid;" ], [ 0, "&DoubleVerticalBar;" ], [ 0, "&NotDoubleVerticalBar;" ], [ 0, "&and;" ], [ 0, "&or;" ], [ 0, {
  v: "&cap;",
  n: 65024,
  o: "&caps;"
} ], [ 0, {
  v: "&cup;",
  n: 65024,
  o: "&cups;"
} ], [ 0, "&int;" ], [ 0, "&Int;" ], [ 0, "&iiint;" ], [ 0, "&conint;" ], [ 0, "&Conint;" ], [ 0, "&Cconint;" ], [ 0, "&cwint;" ], [ 0, "&ClockwiseContourIntegral;" ], [ 0, "&awconint;" ], [ 0, "&there4;" ], [ 0, "&becaus;" ], [ 0, "&ratio;" ], [ 0, "&Colon;" ], [ 0, "&dotminus;" ], [ 1, "&mDDot;" ], [ 0, "&homtht;" ], [ 0, {
  v: "&sim;",
  n: 8402,
  o: "&nvsim;"
} ], [ 0, {
  v: "&backsim;",
  n: 817,
  o: "&race;"
} ], [ 0, {
  v: "&ac;",
  n: 819,
  o: "&acE;"
} ], [ 0, "&acd;" ], [ 0, "&VerticalTilde;" ], [ 0, "&NotTilde;" ], [ 0, {
  v: "&eqsim;",
  n: 824,
  o: "&nesim;"
} ], [ 0, "&sime;" ], [ 0, "&NotTildeEqual;" ], [ 0, "&cong;" ], [ 0, "&simne;" ], [ 0, "&ncong;" ], [ 0, "&ap;" ], [ 0, "&nap;" ], [ 0, "&ape;" ], [ 0, {
  v: "&apid;",
  n: 824,
  o: "&napid;"
} ], [ 0, "&backcong;" ], [ 0, {
  v: "&asympeq;",
  n: 8402,
  o: "&nvap;"
} ], [ 0, {
  v: "&bump;",
  n: 824,
  o: "&nbump;"
} ], [ 0, {
  v: "&bumpe;",
  n: 824,
  o: "&nbumpe;"
} ], [ 0, {
  v: "&doteq;",
  n: 824,
  o: "&nedot;"
} ], [ 0, "&doteqdot;" ], [ 0, "&efDot;" ], [ 0, "&erDot;" ], [ 0, "&Assign;" ], [ 0, "&ecolon;" ], [ 0, "&ecir;" ], [ 0, "&circeq;" ], [ 1, "&wedgeq;" ], [ 0, "&veeeq;" ], [ 1, "&triangleq;" ], [ 2, "&equest;" ], [ 0, "&ne;" ], [ 0, {
  v: "&Congruent;",
  n: 8421,
  o: "&bnequiv;"
} ], [ 0, "&nequiv;" ], [ 1, {
  v: "&le;",
  n: 8402,
  o: "&nvle;"
} ], [ 0, {
  v: "&ge;",
  n: 8402,
  o: "&nvge;"
} ], [ 0, {
  v: "&lE;",
  n: 824,
  o: "&nlE;"
} ], [ 0, {
  v: "&gE;",
  n: 824,
  o: "&ngE;"
} ], [ 0, {
  v: "&lnE;",
  n: 65024,
  o: "&lvertneqq;"
} ], [ 0, {
  v: "&gnE;",
  n: 65024,
  o: "&gvertneqq;"
} ], [ 0, {
  v: "&ll;",
  n: new Map(restoreDiff([ [ 824, "&nLtv;" ], [ 7577, "&nLt;" ] ]))
} ], [ 0, {
  v: "&gg;",
  n: new Map(restoreDiff([ [ 824, "&nGtv;" ], [ 7577, "&nGt;" ] ]))
} ], [ 0, "&between;" ], [ 0, "&NotCupCap;" ], [ 0, "&nless;" ], [ 0, "&ngt;" ], [ 0, "&nle;" ], [ 0, "&nge;" ], [ 0, "&lesssim;" ], [ 0, "&GreaterTilde;" ], [ 0, "&nlsim;" ], [ 0, "&ngsim;" ], [ 0, "&LessGreater;" ], [ 0, "&gl;" ], [ 0, "&NotLessGreater;" ], [ 0, "&NotGreaterLess;" ], [ 0, "&pr;" ], [ 0, "&sc;" ], [ 0, "&prcue;" ], [ 0, "&sccue;" ], [ 0, "&PrecedesTilde;" ], [ 0, {
  v: "&scsim;",
  n: 824,
  o: "&NotSucceedsTilde;"
} ], [ 0, "&NotPrecedes;" ], [ 0, "&NotSucceeds;" ], [ 0, {
  v: "&sub;",
  n: 8402,
  o: "&NotSubset;"
} ], [ 0, {
  v: "&sup;",
  n: 8402,
  o: "&NotSuperset;"
} ], [ 0, "&nsub;" ], [ 0, "&nsup;" ], [ 0, "&sube;" ], [ 0, "&supe;" ], [ 0, "&NotSubsetEqual;" ], [ 0, "&NotSupersetEqual;" ], [ 0, {
  v: "&subne;",
  n: 65024,
  o: "&varsubsetneq;"
} ], [ 0, {
  v: "&supne;",
  n: 65024,
  o: "&varsupsetneq;"
} ], [ 1, "&cupdot;" ], [ 0, "&UnionPlus;" ], [ 0, {
  v: "&sqsub;",
  n: 824,
  o: "&NotSquareSubset;"
} ], [ 0, {
  v: "&sqsup;",
  n: 824,
  o: "&NotSquareSuperset;"
} ], [ 0, "&sqsube;" ], [ 0, "&sqsupe;" ], [ 0, {
  v: "&sqcap;",
  n: 65024,
  o: "&sqcaps;"
} ], [ 0, {
  v: "&sqcup;",
  n: 65024,
  o: "&sqcups;"
} ], [ 0, "&CirclePlus;" ], [ 0, "&CircleMinus;" ], [ 0, "&CircleTimes;" ], [ 0, "&osol;" ], [ 0, "&CircleDot;" ], [ 0, "&circledcirc;" ], [ 0, "&circledast;" ], [ 1, "&circleddash;" ], [ 0, "&boxplus;" ], [ 0, "&boxminus;" ], [ 0, "&boxtimes;" ], [ 0, "&dotsquare;" ], [ 0, "&RightTee;" ], [ 0, "&dashv;" ], [ 0, "&DownTee;" ], [ 0, "&bot;" ], [ 1, "&models;" ], [ 0, "&DoubleRightTee;" ], [ 0, "&Vdash;" ], [ 0, "&Vvdash;" ], [ 0, "&VDash;" ], [ 0, "&nvdash;" ], [ 0, "&nvDash;" ], [ 0, "&nVdash;" ], [ 0, "&nVDash;" ], [ 0, "&prurel;" ], [ 1, "&LeftTriangle;" ], [ 0, "&RightTriangle;" ], [ 0, {
  v: "&LeftTriangleEqual;",
  n: 8402,
  o: "&nvltrie;"
} ], [ 0, {
  v: "&RightTriangleEqual;",
  n: 8402,
  o: "&nvrtrie;"
} ], [ 0, "&origof;" ], [ 0, "&imof;" ], [ 0, "&multimap;" ], [ 0, "&hercon;" ], [ 0, "&intcal;" ], [ 0, "&veebar;" ], [ 1, "&barvee;" ], [ 0, "&angrtvb;" ], [ 0, "&lrtri;" ], [ 0, "&bigwedge;" ], [ 0, "&bigvee;" ], [ 0, "&bigcap;" ], [ 0, "&bigcup;" ], [ 0, "&diam;" ], [ 0, "&sdot;" ], [ 0, "&sstarf;" ], [ 0, "&divideontimes;" ], [ 0, "&bowtie;" ], [ 0, "&ltimes;" ], [ 0, "&rtimes;" ], [ 0, "&leftthreetimes;" ], [ 0, "&rightthreetimes;" ], [ 0, "&backsimeq;" ], [ 0, "&curlyvee;" ], [ 0, "&curlywedge;" ], [ 0, "&Sub;" ], [ 0, "&Sup;" ], [ 0, "&Cap;" ], [ 0, "&Cup;" ], [ 0, "&fork;" ], [ 0, "&epar;" ], [ 0, "&lessdot;" ], [ 0, "&gtdot;" ], [ 0, {
  v: "&Ll;",
  n: 824,
  o: "&nLl;"
} ], [ 0, {
  v: "&Gg;",
  n: 824,
  o: "&nGg;"
} ], [ 0, {
  v: "&leg;",
  n: 65024,
  o: "&lesg;"
} ], [ 0, {
  v: "&gel;",
  n: 65024,
  o: "&gesl;"
} ], [ 2, "&cuepr;" ], [ 0, "&cuesc;" ], [ 0, "&NotPrecedesSlantEqual;" ], [ 0, "&NotSucceedsSlantEqual;" ], [ 0, "&NotSquareSubsetEqual;" ], [ 0, "&NotSquareSupersetEqual;" ], [ 2, "&lnsim;" ], [ 0, "&gnsim;" ], [ 0, "&precnsim;" ], [ 0, "&scnsim;" ], [ 0, "&nltri;" ], [ 0, "&NotRightTriangle;" ], [ 0, "&nltrie;" ], [ 0, "&NotRightTriangleEqual;" ], [ 0, "&vellip;" ], [ 0, "&ctdot;" ], [ 0, "&utdot;" ], [ 0, "&dtdot;" ], [ 0, "&disin;" ], [ 0, "&isinsv;" ], [ 0, "&isins;" ], [ 0, {
  v: "&isindot;",
  n: 824,
  o: "&notindot;"
} ], [ 0, "&notinvc;" ], [ 0, "&notinvb;" ], [ 1, {
  v: "&isinE;",
  n: 824,
  o: "&notinE;"
} ], [ 0, "&nisd;" ], [ 0, "&xnis;" ], [ 0, "&nis;" ], [ 0, "&notnivc;" ], [ 0, "&notnivb;" ], [ 6, "&barwed;" ], [ 0, "&Barwed;" ], [ 1, "&lceil;" ], [ 0, "&rceil;" ], [ 0, "&LeftFloor;" ], [ 0, "&rfloor;" ], [ 0, "&drcrop;" ], [ 0, "&dlcrop;" ], [ 0, "&urcrop;" ], [ 0, "&ulcrop;" ], [ 0, "&bnot;" ], [ 1, "&profline;" ], [ 0, "&profsurf;" ], [ 1, "&telrec;" ], [ 0, "&target;" ], [ 5, "&ulcorn;" ], [ 0, "&urcorn;" ], [ 0, "&dlcorn;" ], [ 0, "&drcorn;" ], [ 2, "&frown;" ], [ 0, "&smile;" ], [ 9, "&cylcty;" ], [ 0, "&profalar;" ], [ 7, "&topbot;" ], [ 6, "&ovbar;" ], [ 1, "&solbar;" ], [ 60, "&angzarr;" ], [ 51, "&lmoustache;" ], [ 0, "&rmoustache;" ], [ 2, "&OverBracket;" ], [ 0, "&bbrk;" ], [ 0, "&bbrktbrk;" ], [ 37, "&OverParenthesis;" ], [ 0, "&UnderParenthesis;" ], [ 0, "&OverBrace;" ], [ 0, "&UnderBrace;" ], [ 2, "&trpezium;" ], [ 4, "&elinters;" ], [ 59, "&blank;" ], [ 164, "&circledS;" ], [ 55, "&boxh;" ], [ 1, "&boxv;" ], [ 9, "&boxdr;" ], [ 3, "&boxdl;" ], [ 3, "&boxur;" ], [ 3, "&boxul;" ], [ 3, "&boxvr;" ], [ 7, "&boxvl;" ], [ 7, "&boxhd;" ], [ 7, "&boxhu;" ], [ 7, "&boxvh;" ], [ 19, "&boxH;" ], [ 0, "&boxV;" ], [ 0, "&boxdR;" ], [ 0, "&boxDr;" ], [ 0, "&boxDR;" ], [ 0, "&boxdL;" ], [ 0, "&boxDl;" ], [ 0, "&boxDL;" ], [ 0, "&boxuR;" ], [ 0, "&boxUr;" ], [ 0, "&boxUR;" ], [ 0, "&boxuL;" ], [ 0, "&boxUl;" ], [ 0, "&boxUL;" ], [ 0, "&boxvR;" ], [ 0, "&boxVr;" ], [ 0, "&boxVR;" ], [ 0, "&boxvL;" ], [ 0, "&boxVl;" ], [ 0, "&boxVL;" ], [ 0, "&boxHd;" ], [ 0, "&boxhD;" ], [ 0, "&boxHD;" ], [ 0, "&boxHu;" ], [ 0, "&boxhU;" ], [ 0, "&boxHU;" ], [ 0, "&boxvH;" ], [ 0, "&boxVh;" ], [ 0, "&boxVH;" ], [ 19, "&uhblk;" ], [ 3, "&lhblk;" ], [ 3, "&block;" ], [ 8, "&blk14;" ], [ 0, "&blk12;" ], [ 0, "&blk34;" ], [ 13, "&square;" ], [ 8, "&blacksquare;" ], [ 0, "&EmptyVerySmallSquare;" ], [ 1, "&rect;" ], [ 0, "&marker;" ], [ 2, "&fltns;" ], [ 1, "&bigtriangleup;" ], [ 0, "&blacktriangle;" ], [ 0, "&triangle;" ], [ 2, "&blacktriangleright;" ], [ 0, "&rtri;" ], [ 3, "&bigtriangledown;" ], [ 0, "&blacktriangledown;" ], [ 0, "&dtri;" ], [ 2, "&blacktriangleleft;" ], [ 0, "&ltri;" ], [ 6, "&loz;" ], [ 0, "&cir;" ], [ 32, "&tridot;" ], [ 2, "&bigcirc;" ], [ 8, "&ultri;" ], [ 0, "&urtri;" ], [ 0, "&lltri;" ], [ 0, "&EmptySmallSquare;" ], [ 0, "&FilledSmallSquare;" ], [ 8, "&bigstar;" ], [ 0, "&star;" ], [ 7, "&phone;" ], [ 49, "&female;" ], [ 1, "&male;" ], [ 29, "&spades;" ], [ 2, "&clubs;" ], [ 1, "&hearts;" ], [ 0, "&diamondsuit;" ], [ 3, "&sung;" ], [ 2, "&flat;" ], [ 0, "&natural;" ], [ 0, "&sharp;" ], [ 163, "&check;" ], [ 3, "&cross;" ], [ 8, "&malt;" ], [ 21, "&sext;" ], [ 33, "&VerticalSeparator;" ], [ 25, "&lbbrk;" ], [ 0, "&rbbrk;" ], [ 84, "&bsolhsub;" ], [ 0, "&suphsol;" ], [ 28, "&LeftDoubleBracket;" ], [ 0, "&RightDoubleBracket;" ], [ 0, "&lang;" ], [ 0, "&rang;" ], [ 0, "&Lang;" ], [ 0, "&Rang;" ], [ 0, "&loang;" ], [ 0, "&roang;" ], [ 7, "&longleftarrow;" ], [ 0, "&longrightarrow;" ], [ 0, "&longleftrightarrow;" ], [ 0, "&DoubleLongLeftArrow;" ], [ 0, "&DoubleLongRightArrow;" ], [ 0, "&DoubleLongLeftRightArrow;" ], [ 1, "&longmapsto;" ], [ 2, "&dzigrarr;" ], [ 258, "&nvlArr;" ], [ 0, "&nvrArr;" ], [ 0, "&nvHarr;" ], [ 0, "&Map;" ], [ 6, "&lbarr;" ], [ 0, "&bkarow;" ], [ 0, "&lBarr;" ], [ 0, "&dbkarow;" ], [ 0, "&drbkarow;" ], [ 0, "&DDotrahd;" ], [ 0, "&UpArrowBar;" ], [ 0, "&DownArrowBar;" ], [ 2, "&Rarrtl;" ], [ 2, "&latail;" ], [ 0, "&ratail;" ], [ 0, "&lAtail;" ], [ 0, "&rAtail;" ], [ 0, "&larrfs;" ], [ 0, "&rarrfs;" ], [ 0, "&larrbfs;" ], [ 0, "&rarrbfs;" ], [ 2, "&nwarhk;" ], [ 0, "&nearhk;" ], [ 0, "&hksearow;" ], [ 0, "&hkswarow;" ], [ 0, "&nwnear;" ], [ 0, "&nesear;" ], [ 0, "&seswar;" ], [ 0, "&swnwar;" ], [ 8, {
  v: "&rarrc;",
  n: 824,
  o: "&nrarrc;"
} ], [ 1, "&cudarrr;" ], [ 0, "&ldca;" ], [ 0, "&rdca;" ], [ 0, "&cudarrl;" ], [ 0, "&larrpl;" ], [ 2, "&curarrm;" ], [ 0, "&cularrp;" ], [ 7, "&rarrpl;" ], [ 2, "&harrcir;" ], [ 0, "&Uarrocir;" ], [ 0, "&lurdshar;" ], [ 0, "&ldrushar;" ], [ 2, "&LeftRightVector;" ], [ 0, "&RightUpDownVector;" ], [ 0, "&DownLeftRightVector;" ], [ 0, "&LeftUpDownVector;" ], [ 0, "&LeftVectorBar;" ], [ 0, "&RightVectorBar;" ], [ 0, "&RightUpVectorBar;" ], [ 0, "&RightDownVectorBar;" ], [ 0, "&DownLeftVectorBar;" ], [ 0, "&DownRightVectorBar;" ], [ 0, "&LeftUpVectorBar;" ], [ 0, "&LeftDownVectorBar;" ], [ 0, "&LeftTeeVector;" ], [ 0, "&RightTeeVector;" ], [ 0, "&RightUpTeeVector;" ], [ 0, "&RightDownTeeVector;" ], [ 0, "&DownLeftTeeVector;" ], [ 0, "&DownRightTeeVector;" ], [ 0, "&LeftUpTeeVector;" ], [ 0, "&LeftDownTeeVector;" ], [ 0, "&lHar;" ], [ 0, "&uHar;" ], [ 0, "&rHar;" ], [ 0, "&dHar;" ], [ 0, "&luruhar;" ], [ 0, "&ldrdhar;" ], [ 0, "&ruluhar;" ], [ 0, "&rdldhar;" ], [ 0, "&lharul;" ], [ 0, "&llhard;" ], [ 0, "&rharul;" ], [ 0, "&lrhard;" ], [ 0, "&udhar;" ], [ 0, "&duhar;" ], [ 0, "&RoundImplies;" ], [ 0, "&erarr;" ], [ 0, "&simrarr;" ], [ 0, "&larrsim;" ], [ 0, "&rarrsim;" ], [ 0, "&rarrap;" ], [ 0, "&ltlarr;" ], [ 1, "&gtrarr;" ], [ 0, "&subrarr;" ], [ 1, "&suplarr;" ], [ 0, "&lfisht;" ], [ 0, "&rfisht;" ], [ 0, "&ufisht;" ], [ 0, "&dfisht;" ], [ 5, "&lopar;" ], [ 0, "&ropar;" ], [ 4, "&lbrke;" ], [ 0, "&rbrke;" ], [ 0, "&lbrkslu;" ], [ 0, "&rbrksld;" ], [ 0, "&lbrksld;" ], [ 0, "&rbrkslu;" ], [ 0, "&langd;" ], [ 0, "&rangd;" ], [ 0, "&lparlt;" ], [ 0, "&rpargt;" ], [ 0, "&gtlPar;" ], [ 0, "&ltrPar;" ], [ 3, "&vzigzag;" ], [ 1, "&vangrt;" ], [ 0, "&angrtvbd;" ], [ 6, "&ange;" ], [ 0, "&range;" ], [ 0, "&dwangle;" ], [ 0, "&uwangle;" ], [ 0, "&angmsdaa;" ], [ 0, "&angmsdab;" ], [ 0, "&angmsdac;" ], [ 0, "&angmsdad;" ], [ 0, "&angmsdae;" ], [ 0, "&angmsdaf;" ], [ 0, "&angmsdag;" ], [ 0, "&angmsdah;" ], [ 0, "&bemptyv;" ], [ 0, "&demptyv;" ], [ 0, "&cemptyv;" ], [ 0, "&raemptyv;" ], [ 0, "&laemptyv;" ], [ 0, "&ohbar;" ], [ 0, "&omid;" ], [ 0, "&opar;" ], [ 1, "&operp;" ], [ 1, "&olcross;" ], [ 0, "&odsold;" ], [ 1, "&olcir;" ], [ 0, "&ofcir;" ], [ 0, "&olt;" ], [ 0, "&ogt;" ], [ 0, "&cirscir;" ], [ 0, "&cirE;" ], [ 0, "&solb;" ], [ 0, "&bsolb;" ], [ 3, "&boxbox;" ], [ 3, "&trisb;" ], [ 0, "&rtriltri;" ], [ 0, {
  v: "&LeftTriangleBar;",
  n: 824,
  o: "&NotLeftTriangleBar;"
} ], [ 0, {
  v: "&RightTriangleBar;",
  n: 824,
  o: "&NotRightTriangleBar;"
} ], [ 11, "&iinfin;" ], [ 0, "&infintie;" ], [ 0, "&nvinfin;" ], [ 4, "&eparsl;" ], [ 0, "&smeparsl;" ], [ 0, "&eqvparsl;" ], [ 5, "&blacklozenge;" ], [ 8, "&RuleDelayed;" ], [ 1, "&dsol;" ], [ 9, "&bigodot;" ], [ 0, "&bigoplus;" ], [ 0, "&bigotimes;" ], [ 1, "&biguplus;" ], [ 1, "&bigsqcup;" ], [ 5, "&iiiint;" ], [ 0, "&fpartint;" ], [ 2, "&cirfnint;" ], [ 0, "&awint;" ], [ 0, "&rppolint;" ], [ 0, "&scpolint;" ], [ 0, "&npolint;" ], [ 0, "&pointint;" ], [ 0, "&quatint;" ], [ 0, "&intlarhk;" ], [ 10, "&pluscir;" ], [ 0, "&plusacir;" ], [ 0, "&simplus;" ], [ 0, "&plusdu;" ], [ 0, "&plussim;" ], [ 0, "&plustwo;" ], [ 1, "&mcomma;" ], [ 0, "&minusdu;" ], [ 2, "&loplus;" ], [ 0, "&roplus;" ], [ 0, "&Cross;" ], [ 0, "&timesd;" ], [ 0, "&timesbar;" ], [ 1, "&smashp;" ], [ 0, "&lotimes;" ], [ 0, "&rotimes;" ], [ 0, "&otimesas;" ], [ 0, "&Otimes;" ], [ 0, "&odiv;" ], [ 0, "&triplus;" ], [ 0, "&triminus;" ], [ 0, "&tritime;" ], [ 0, "&intprod;" ], [ 2, "&amalg;" ], [ 0, "&capdot;" ], [ 1, "&ncup;" ], [ 0, "&ncap;" ], [ 0, "&capand;" ], [ 0, "&cupor;" ], [ 0, "&cupcap;" ], [ 0, "&capcup;" ], [ 0, "&cupbrcap;" ], [ 0, "&capbrcup;" ], [ 0, "&cupcup;" ], [ 0, "&capcap;" ], [ 0, "&ccups;" ], [ 0, "&ccaps;" ], [ 2, "&ccupssm;" ], [ 2, "&And;" ], [ 0, "&Or;" ], [ 0, "&andand;" ], [ 0, "&oror;" ], [ 0, "&orslope;" ], [ 0, "&andslope;" ], [ 1, "&andv;" ], [ 0, "&orv;" ], [ 0, "&andd;" ], [ 0, "&ord;" ], [ 1, "&wedbar;" ], [ 6, "&sdote;" ], [ 3, "&simdot;" ], [ 2, {
  v: "&congdot;",
  n: 824,
  o: "&ncongdot;"
} ], [ 0, "&easter;" ], [ 0, "&apacir;" ], [ 0, {
  v: "&apE;",
  n: 824,
  o: "&napE;"
} ], [ 0, "&eplus;" ], [ 0, "&pluse;" ], [ 0, "&Esim;" ], [ 0, "&Colone;" ], [ 0, "&Equal;" ], [ 1, "&ddotseq;" ], [ 0, "&equivDD;" ], [ 0, "&ltcir;" ], [ 0, "&gtcir;" ], [ 0, "&ltquest;" ], [ 0, "&gtquest;" ], [ 0, {
  v: "&leqslant;",
  n: 824,
  o: "&nleqslant;"
} ], [ 0, {
  v: "&geqslant;",
  n: 824,
  o: "&ngeqslant;"
} ], [ 0, "&lesdot;" ], [ 0, "&gesdot;" ], [ 0, "&lesdoto;" ], [ 0, "&gesdoto;" ], [ 0, "&lesdotor;" ], [ 0, "&gesdotol;" ], [ 0, "&lap;" ], [ 0, "&gap;" ], [ 0, "&lne;" ], [ 0, "&gne;" ], [ 0, "&lnap;" ], [ 0, "&gnap;" ], [ 0, "&lEg;" ], [ 0, "&gEl;" ], [ 0, "&lsime;" ], [ 0, "&gsime;" ], [ 0, "&lsimg;" ], [ 0, "&gsiml;" ], [ 0, "&lgE;" ], [ 0, "&glE;" ], [ 0, "&lesges;" ], [ 0, "&gesles;" ], [ 0, "&els;" ], [ 0, "&egs;" ], [ 0, "&elsdot;" ], [ 0, "&egsdot;" ], [ 0, "&el;" ], [ 0, "&eg;" ], [ 2, "&siml;" ], [ 0, "&simg;" ], [ 0, "&simlE;" ], [ 0, "&simgE;" ], [ 0, {
  v: "&LessLess;",
  n: 824,
  o: "&NotNestedLessLess;"
} ], [ 0, {
  v: "&GreaterGreater;",
  n: 824,
  o: "&NotNestedGreaterGreater;"
} ], [ 1, "&glj;" ], [ 0, "&gla;" ], [ 0, "&ltcc;" ], [ 0, "&gtcc;" ], [ 0, "&lescc;" ], [ 0, "&gescc;" ], [ 0, "&smt;" ], [ 0, "&lat;" ], [ 0, {
  v: "&smte;",
  n: 65024,
  o: "&smtes;"
} ], [ 0, {
  v: "&late;",
  n: 65024,
  o: "&lates;"
} ], [ 0, "&bumpE;" ], [ 0, {
  v: "&PrecedesEqual;",
  n: 824,
  o: "&NotPrecedesEqual;"
} ], [ 0, {
  v: "&sce;",
  n: 824,
  o: "&NotSucceedsEqual;"
} ], [ 2, "&prE;" ], [ 0, "&scE;" ], [ 0, "&precneqq;" ], [ 0, "&scnE;" ], [ 0, "&prap;" ], [ 0, "&scap;" ], [ 0, "&precnapprox;" ], [ 0, "&scnap;" ], [ 0, "&Pr;" ], [ 0, "&Sc;" ], [ 0, "&subdot;" ], [ 0, "&supdot;" ], [ 0, "&subplus;" ], [ 0, "&supplus;" ], [ 0, "&submult;" ], [ 0, "&supmult;" ], [ 0, "&subedot;" ], [ 0, "&supedot;" ], [ 0, {
  v: "&subE;",
  n: 824,
  o: "&nsubE;"
} ], [ 0, {
  v: "&supE;",
  n: 824,
  o: "&nsupE;"
} ], [ 0, "&subsim;" ], [ 0, "&supsim;" ], [ 2, {
  v: "&subnE;",
  n: 65024,
  o: "&varsubsetneqq;"
} ], [ 0, {
  v: "&supnE;",
  n: 65024,
  o: "&varsupsetneqq;"
} ], [ 2, "&csub;" ], [ 0, "&csup;" ], [ 0, "&csube;" ], [ 0, "&csupe;" ], [ 0, "&subsup;" ], [ 0, "&supsub;" ], [ 0, "&subsub;" ], [ 0, "&supsup;" ], [ 0, "&suphsub;" ], [ 0, "&supdsub;" ], [ 0, "&forkv;" ], [ 0, "&topfork;" ], [ 0, "&mlcp;" ], [ 8, "&Dashv;" ], [ 1, "&Vdashl;" ], [ 0, "&Barv;" ], [ 0, "&vBar;" ], [ 0, "&vBarv;" ], [ 1, "&Vbar;" ], [ 0, "&Not;" ], [ 0, "&bNot;" ], [ 0, "&rnmid;" ], [ 0, "&cirmid;" ], [ 0, "&midcir;" ], [ 0, "&topcir;" ], [ 0, "&nhpar;" ], [ 0, "&parsim;" ], [ 9, {
  v: "&parsl;",
  n: 8421,
  o: "&nparsl;"
} ], [ 44343, {
  n: new Map(restoreDiff([ [ 56476, "&Ascr;" ], [ 1, "&Cscr;" ], [ 0, "&Dscr;" ], [ 2, "&Gscr;" ], [ 2, "&Jscr;" ], [ 0, "&Kscr;" ], [ 2, "&Nscr;" ], [ 0, "&Oscr;" ], [ 0, "&Pscr;" ], [ 0, "&Qscr;" ], [ 1, "&Sscr;" ], [ 0, "&Tscr;" ], [ 0, "&Uscr;" ], [ 0, "&Vscr;" ], [ 0, "&Wscr;" ], [ 0, "&Xscr;" ], [ 0, "&Yscr;" ], [ 0, "&Zscr;" ], [ 0, "&ascr;" ], [ 0, "&bscr;" ], [ 0, "&cscr;" ], [ 0, "&dscr;" ], [ 1, "&fscr;" ], [ 1, "&hscr;" ], [ 0, "&iscr;" ], [ 0, "&jscr;" ], [ 0, "&kscr;" ], [ 0, "&lscr;" ], [ 0, "&mscr;" ], [ 0, "&nscr;" ], [ 1, "&pscr;" ], [ 0, "&qscr;" ], [ 0, "&rscr;" ], [ 0, "&sscr;" ], [ 0, "&tscr;" ], [ 0, "&uscr;" ], [ 0, "&vscr;" ], [ 0, "&wscr;" ], [ 0, "&xscr;" ], [ 0, "&yscr;" ], [ 0, "&zscr;" ], [ 52, "&Afr;" ], [ 0, "&Bfr;" ], [ 1, "&Dfr;" ], [ 0, "&Efr;" ], [ 0, "&Ffr;" ], [ 0, "&Gfr;" ], [ 2, "&Jfr;" ], [ 0, "&Kfr;" ], [ 0, "&Lfr;" ], [ 0, "&Mfr;" ], [ 0, "&Nfr;" ], [ 0, "&Ofr;" ], [ 0, "&Pfr;" ], [ 0, "&Qfr;" ], [ 1, "&Sfr;" ], [ 0, "&Tfr;" ], [ 0, "&Ufr;" ], [ 0, "&Vfr;" ], [ 0, "&Wfr;" ], [ 0, "&Xfr;" ], [ 0, "&Yfr;" ], [ 1, "&afr;" ], [ 0, "&bfr;" ], [ 0, "&cfr;" ], [ 0, "&dfr;" ], [ 0, "&efr;" ], [ 0, "&ffr;" ], [ 0, "&gfr;" ], [ 0, "&hfr;" ], [ 0, "&ifr;" ], [ 0, "&jfr;" ], [ 0, "&kfr;" ], [ 0, "&lfr;" ], [ 0, "&mfr;" ], [ 0, "&nfr;" ], [ 0, "&ofr;" ], [ 0, "&pfr;" ], [ 0, "&qfr;" ], [ 0, "&rfr;" ], [ 0, "&sfr;" ], [ 0, "&tfr;" ], [ 0, "&ufr;" ], [ 0, "&vfr;" ], [ 0, "&wfr;" ], [ 0, "&xfr;" ], [ 0, "&yfr;" ], [ 0, "&zfr;" ], [ 0, "&Aopf;" ], [ 0, "&Bopf;" ], [ 1, "&Dopf;" ], [ 0, "&Eopf;" ], [ 0, "&Fopf;" ], [ 0, "&Gopf;" ], [ 1, "&Iopf;" ], [ 0, "&Jopf;" ], [ 0, "&Kopf;" ], [ 0, "&Lopf;" ], [ 0, "&Mopf;" ], [ 1, "&Oopf;" ], [ 3, "&Sopf;" ], [ 0, "&Topf;" ], [ 0, "&Uopf;" ], [ 0, "&Vopf;" ], [ 0, "&Wopf;" ], [ 0, "&Xopf;" ], [ 0, "&Yopf;" ], [ 1, "&aopf;" ], [ 0, "&bopf;" ], [ 0, "&copf;" ], [ 0, "&dopf;" ], [ 0, "&eopf;" ], [ 0, "&fopf;" ], [ 0, "&gopf;" ], [ 0, "&hopf;" ], [ 0, "&iopf;" ], [ 0, "&jopf;" ], [ 0, "&kopf;" ], [ 0, "&lopf;" ], [ 0, "&mopf;" ], [ 0, "&nopf;" ], [ 0, "&oopf;" ], [ 0, "&popf;" ], [ 0, "&qopf;" ], [ 0, "&ropf;" ], [ 0, "&sopf;" ], [ 0, "&topf;" ], [ 0, "&uopf;" ], [ 0, "&vopf;" ], [ 0, "&wopf;" ], [ 0, "&xopf;" ], [ 0, "&yopf;" ], [ 0, "&zopf;" ] ]))
} ], [ 8906, "&fflig;" ], [ 0, "&filig;" ], [ 0, "&fllig;" ], [ 0, "&ffilig;" ], [ 0, "&ffllig;" ] ]));

var _escape = {};

!function(exports) {
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.escapeText = exports.escapeAttribute = exports.escapeUTF8 = exports.escape = exports.encodeXML = exports.getCodePoint = exports.xmlReplacer = void 0, 
  exports.xmlReplacer = /["&'<>$\x80-\uFFFF]/g;
  var xmlCodeMap = new Map([ [ 34, "&quot;" ], [ 38, "&amp;" ], [ 39, "&apos;" ], [ 60, "&lt;" ], [ 62, "&gt;" ] ]);
  function encodeXML(str) {
    for (var match, ret = "", lastIdx = 0; null !== (match = exports.xmlReplacer.exec(str)); ) {
      var i = match.index, char = str.charCodeAt(i), next = xmlCodeMap.get(char);
      void 0 !== next ? (ret += str.substring(lastIdx, i) + next, lastIdx = i + 1) : (ret += "".concat(str.substring(lastIdx, i), "&#x").concat((0, 
      exports.getCodePoint)(str, i).toString(16), ";"), lastIdx = exports.xmlReplacer.lastIndex += Number(0xd800 == (0xfc00 & char)));
    }
    return ret + str.substr(lastIdx);
  }
  function getEscaper(regex, map) {
    return function(data) {
      for (var match, lastIdx = 0, result = ""; match = regex.exec(data); ) lastIdx !== match.index && (result += data.substring(lastIdx, match.index)), 
      result += map.get(match[0].charCodeAt(0)), lastIdx = match.index + 1;
      return result + data.substring(lastIdx);
    };
  }
  exports.getCodePoint = null != String.prototype.codePointAt ? function(str, index) {
    return str.codePointAt(index);
  } : function(c, index) {
    return 0xd800 == (0xfc00 & c.charCodeAt(index)) ? 0x400 * (c.charCodeAt(index) - 0xd800) + c.charCodeAt(index + 1) - 0xdc00 + 0x10000 : c.charCodeAt(index);
  }, exports.encodeXML = encodeXML, exports.escape = encodeXML, exports.escapeUTF8 = getEscaper(/[&<>'"]/g, xmlCodeMap), 
  exports.escapeAttribute = getEscaper(/["&\u00A0]/g, new Map([ [ 34, "&quot;" ], [ 38, "&amp;" ], [ 160, "&nbsp;" ] ])), 
  exports.escapeText = getEscaper(/[&<>\u00A0]/g, new Map([ [ 38, "&amp;" ], [ 60, "&lt;" ], [ 62, "&gt;" ], [ 160, "&nbsp;" ] ]));
}(_escape);

var __importDefault$4 = commonjsGlobal && commonjsGlobal.__importDefault || function(mod) {
  return mod && mod.__esModule ? mod : {
    "default": mod
  };
};

Object.defineProperty(encode$6, "__esModule", {
  value: !0
}), encode$6.encodeNonAsciiHTML = encode$6.encodeHTML = void 0;

var encode_html_js_1 = __importDefault$4(encodeHtml), escape_js_1 = _escape, htmlReplacer = /[\t\n!-,./:-@[-`\f{-}$\x80-\uFFFF]/g;

function encodeHTMLTrieRe(regExp, str) {
  for (var match, ret = "", lastIdx = 0; null !== (match = regExp.exec(str)); ) {
    var i = match.index;
    ret += str.substring(lastIdx, i);
    var char = str.charCodeAt(i), next = encode_html_js_1.default.get(char);
    if ("object" == typeof next) {
      if (i + 1 < str.length) {
        var nextChar = str.charCodeAt(i + 1), value = "number" == typeof next.n ? next.n === nextChar ? next.o : void 0 : next.n.get(nextChar);
        if (void 0 !== value) {
          ret += value, lastIdx = regExp.lastIndex += 1;
          continue;
        }
      }
      next = next.v;
    }
    if (void 0 !== next) ret += next, lastIdx = i + 1; else {
      var cp = (0, escape_js_1.getCodePoint)(str, i);
      ret += "&#x".concat(cp.toString(16), ";"), lastIdx = regExp.lastIndex += Number(cp !== char);
    }
  }
  return ret + str.substr(lastIdx);
}

encode$6.encodeHTML = function(data) {
  return encodeHTMLTrieRe(htmlReplacer, data);
}, encode$6.encodeNonAsciiHTML = function(data) {
  return encodeHTMLTrieRe(escape_js_1.xmlReplacer, data);
}, function(exports) {
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.decodeXMLStrict = exports.decodeHTML5Strict = exports.decodeHTML4Strict = exports.decodeHTML5 = exports.decodeHTML4 = exports.decodeHTMLAttribute = exports.decodeHTMLStrict = exports.decodeHTML = exports.decodeXML = exports.DecodingMode = exports.EntityDecoder = exports.encodeHTML5 = exports.encodeHTML4 = exports.encodeNonAsciiHTML = exports.encodeHTML = exports.escapeText = exports.escapeAttribute = exports.escapeUTF8 = exports.escape = exports.encodeXML = exports.encode = exports.decodeStrict = exports.decode = exports.EncodingMode = exports.EntityLevel = void 0;
  var EntityLevel, EncodingMode, decode_js_1 = decode$6, encode_js_1 = encode$6, escape_js_1 = _escape;
  function decode(data, options) {
    if (void 0 === options && (options = EntityLevel.XML), ("number" == typeof options ? options : options.level) === EntityLevel.HTML) {
      var mode = "object" == typeof options ? options.mode : void 0;
      return (0, decode_js_1.decodeHTML)(data, mode);
    }
    return (0, decode_js_1.decodeXML)(data);
  }
  !function(EntityLevel) {
    EntityLevel[EntityLevel.XML = 0] = "XML", EntityLevel[EntityLevel.HTML = 1] = "HTML";
  }(EntityLevel = exports.EntityLevel || (exports.EntityLevel = {})), function(EncodingMode) {
    EncodingMode[EncodingMode.UTF8 = 0] = "UTF8", EncodingMode[EncodingMode.ASCII = 1] = "ASCII", 
    EncodingMode[EncodingMode.Extensive = 2] = "Extensive", EncodingMode[EncodingMode.Attribute = 3] = "Attribute", 
    EncodingMode[EncodingMode.Text = 4] = "Text";
  }(EncodingMode = exports.EncodingMode || (exports.EncodingMode = {})), exports.decode = decode, 
  exports.decodeStrict = function(data, options) {
    var _a;
    void 0 === options && (options = EntityLevel.XML);
    var opts = "number" == typeof options ? {
      level: options
    } : options;
    return null !== (_a = opts.mode) && void 0 !== _a || (opts.mode = decode_js_1.DecodingMode.Strict), 
    decode(data, opts);
  }, exports.encode = function(data, options) {
    void 0 === options && (options = EntityLevel.XML);
    var opts = "number" == typeof options ? {
      level: options
    } : options;
    return opts.mode === EncodingMode.UTF8 ? (0, escape_js_1.escapeUTF8)(data) : opts.mode === EncodingMode.Attribute ? (0, 
    escape_js_1.escapeAttribute)(data) : opts.mode === EncodingMode.Text ? (0, escape_js_1.escapeText)(data) : opts.level === EntityLevel.HTML ? opts.mode === EncodingMode.ASCII ? (0, 
    encode_js_1.encodeNonAsciiHTML)(data) : (0, encode_js_1.encodeHTML)(data) : (0, 
    escape_js_1.encodeXML)(data);
  };
  var escape_js_2 = _escape;
  Object.defineProperty(exports, "encodeXML", {
    enumerable: !0,
    get: function() {
      return escape_js_2.encodeXML;
    }
  }), Object.defineProperty(exports, "escape", {
    enumerable: !0,
    get: function() {
      return escape_js_2.escape;
    }
  }), Object.defineProperty(exports, "escapeUTF8", {
    enumerable: !0,
    get: function() {
      return escape_js_2.escapeUTF8;
    }
  }), Object.defineProperty(exports, "escapeAttribute", {
    enumerable: !0,
    get: function() {
      return escape_js_2.escapeAttribute;
    }
  }), Object.defineProperty(exports, "escapeText", {
    enumerable: !0,
    get: function() {
      return escape_js_2.escapeText;
    }
  });
  var encode_js_2 = encode$6;
  Object.defineProperty(exports, "encodeHTML", {
    enumerable: !0,
    get: function() {
      return encode_js_2.encodeHTML;
    }
  }), Object.defineProperty(exports, "encodeNonAsciiHTML", {
    enumerable: !0,
    get: function() {
      return encode_js_2.encodeNonAsciiHTML;
    }
  }), Object.defineProperty(exports, "encodeHTML4", {
    enumerable: !0,
    get: function() {
      return encode_js_2.encodeHTML;
    }
  }), Object.defineProperty(exports, "encodeHTML5", {
    enumerable: !0,
    get: function() {
      return encode_js_2.encodeHTML;
    }
  });
  var decode_js_2 = decode$6;
  Object.defineProperty(exports, "EntityDecoder", {
    enumerable: !0,
    get: function() {
      return decode_js_2.EntityDecoder;
    }
  }), Object.defineProperty(exports, "DecodingMode", {
    enumerable: !0,
    get: function() {
      return decode_js_2.DecodingMode;
    }
  }), Object.defineProperty(exports, "decodeXML", {
    enumerable: !0,
    get: function() {
      return decode_js_2.decodeXML;
    }
  }), Object.defineProperty(exports, "decodeHTML", {
    enumerable: !0,
    get: function() {
      return decode_js_2.decodeHTML;
    }
  }), Object.defineProperty(exports, "decodeHTMLStrict", {
    enumerable: !0,
    get: function() {
      return decode_js_2.decodeHTMLStrict;
    }
  }), Object.defineProperty(exports, "decodeHTMLAttribute", {
    enumerable: !0,
    get: function() {
      return decode_js_2.decodeHTMLAttribute;
    }
  }), Object.defineProperty(exports, "decodeHTML4", {
    enumerable: !0,
    get: function() {
      return decode_js_2.decodeHTML;
    }
  }), Object.defineProperty(exports, "decodeHTML5", {
    enumerable: !0,
    get: function() {
      return decode_js_2.decodeHTML;
    }
  }), Object.defineProperty(exports, "decodeHTML4Strict", {
    enumerable: !0,
    get: function() {
      return decode_js_2.decodeHTMLStrict;
    }
  }), Object.defineProperty(exports, "decodeHTML5Strict", {
    enumerable: !0,
    get: function() {
      return decode_js_2.decodeHTMLStrict;
    }
  }), Object.defineProperty(exports, "decodeXMLStrict", {
    enumerable: !0,
    get: function() {
      return decode_js_2.decodeXML;
    }
  });
}(lib$1);

var foreignNames = {};

Object.defineProperty(foreignNames, "__esModule", {
  value: !0
}), foreignNames.attributeNames = foreignNames.elementNames = void 0, foreignNames.elementNames = new Map([ "altGlyph", "altGlyphDef", "altGlyphItem", "animateColor", "animateMotion", "animateTransform", "clipPath", "feBlend", "feColorMatrix", "feComponentTransfer", "feComposite", "feConvolveMatrix", "feDiffuseLighting", "feDisplacementMap", "feDistantLight", "feDropShadow", "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR", "feGaussianBlur", "feImage", "feMerge", "feMergeNode", "feMorphology", "feOffset", "fePointLight", "feSpecularLighting", "feSpotLight", "feTile", "feTurbulence", "foreignObject", "glyphRef", "linearGradient", "radialGradient", "textPath" ].map((function(val) {
  return [ val.toLowerCase(), val ];
}))), foreignNames.attributeNames = new Map([ "definitionURL", "attributeName", "attributeType", "baseFrequency", "baseProfile", "calcMode", "clipPathUnits", "diffuseConstant", "edgeMode", "filterUnits", "glyphRef", "gradientTransform", "gradientUnits", "kernelMatrix", "kernelUnitLength", "keyPoints", "keySplines", "keyTimes", "lengthAdjust", "limitingConeAngle", "markerHeight", "markerUnits", "markerWidth", "maskContentUnits", "maskUnits", "numOctaves", "pathLength", "patternContentUnits", "patternTransform", "patternUnits", "pointsAtX", "pointsAtY", "pointsAtZ", "preserveAlpha", "preserveAspectRatio", "primitiveUnits", "refX", "refY", "repeatCount", "repeatDur", "requiredExtensions", "requiredFeatures", "specularConstant", "specularExponent", "spreadMethod", "startOffset", "stdDeviation", "stitchTiles", "surfaceScale", "systemLanguage", "tableValues", "targetX", "targetY", "textLength", "viewBox", "viewTarget", "xChannelSelector", "yChannelSelector", "zoomAndPan" ].map((function(val) {
  return [ val.toLowerCase(), val ];
})));

var __assign = commonjsGlobal && commonjsGlobal.__assign || function() {
  return __assign = Object.assign || function(t) {
    for (var s, i = 1, n = arguments.length; i < n; i++) for (var p in s = arguments[i]) Object.prototype.hasOwnProperty.call(s, p) && (t[p] = s[p]);
    return t;
  }, __assign.apply(this, arguments);
}, __createBinding$1 = commonjsGlobal && commonjsGlobal.__createBinding || (Object.create ? function(o, m, k, k2) {
  void 0 === k2 && (k2 = k);
  var desc = Object.getOwnPropertyDescriptor(m, k);
  desc && !("get" in desc ? !m.__esModule : desc.writable || desc.configurable) || (desc = {
    enumerable: !0,
    get: function() {
      return m[k];
    }
  }), Object.defineProperty(o, k2, desc);
} : function(o, m, k, k2) {
  void 0 === k2 && (k2 = k), o[k2] = m[k];
}), __setModuleDefault$1 = commonjsGlobal && commonjsGlobal.__setModuleDefault || (Object.create ? function(o, v) {
  Object.defineProperty(o, "default", {
    enumerable: !0,
    value: v
  });
} : function(o, v) {
  o.default = v;
}), __importStar$1 = commonjsGlobal && commonjsGlobal.__importStar || function(mod) {
  if (mod && mod.__esModule) return mod;
  var result = {};
  if (null != mod) for (var k in mod) "default" !== k && Object.prototype.hasOwnProperty.call(mod, k) && __createBinding$1(result, mod, k);
  return __setModuleDefault$1(result, mod), result;
};

Object.defineProperty(lib$2, "__esModule", {
  value: !0
}), lib$2.render = void 0;

var ElementType = __importStar$1(lib$3), entities_1 = lib$1, foreignNames_js_1 = foreignNames, unencodedElements = new Set([ "style", "script", "xmp", "iframe", "noembed", "noframes", "plaintext", "noscript" ]);

function replaceQuotes(value) {
  return value.replace(/"/g, "&quot;");
}

var singleTag = new Set([ "area", "base", "basefont", "br", "col", "command", "embed", "frame", "hr", "img", "input", "isindex", "keygen", "link", "meta", "param", "source", "track", "wbr" ]);

function render(node, options) {
  void 0 === options && (options = {});
  for (var nodes = ("length" in node ? node : [ node ]), output = "", i = 0; i < nodes.length; i++) output += renderNode(nodes[i], options);
  return output;
}

function renderNode(node, options) {
  switch (node.type) {
   case ElementType.Root:
    return render(node.children, options);

   case ElementType.Doctype:
   case ElementType.Directive:
    return "<".concat(node.data, ">");

   case ElementType.Comment:
    return function(elem) {
      return "\x3c!--".concat(elem.data, "--\x3e");
    }(node);

   case ElementType.CDATA:
    return function(elem) {
      return "<![CDATA[".concat(elem.children[0].data, "]]>");
    }(node);

   case ElementType.Script:
   case ElementType.Style:
   case ElementType.Tag:
    return function(elem, opts) {
      var _a;
      "foreign" === opts.xmlMode && (elem.name = null !== (_a = foreignNames_js_1.elementNames.get(elem.name)) && void 0 !== _a ? _a : elem.name, 
      elem.parent && foreignModeIntegrationPoints.has(elem.parent.name) && (opts = __assign(__assign({}, opts), {
        xmlMode: !1
      })));
      !opts.xmlMode && foreignElements.has(elem.name) && (opts = __assign(__assign({}, opts), {
        xmlMode: "foreign"
      }));
      var tag = "<".concat(elem.name), attribs = function(attributes, opts) {
        var _a;
        if (attributes) {
          var encode = !1 === (null !== (_a = opts.encodeEntities) && void 0 !== _a ? _a : opts.decodeEntities) ? replaceQuotes : opts.xmlMode || "utf8" !== opts.encodeEntities ? entities_1.encodeXML : entities_1.escapeAttribute;
          return Object.keys(attributes).map((function(key) {
            var _a, _b, value = null !== (_a = attributes[key]) && void 0 !== _a ? _a : "";
            return "foreign" === opts.xmlMode && (key = null !== (_b = foreignNames_js_1.attributeNames.get(key)) && void 0 !== _b ? _b : key), 
            opts.emptyAttrs || opts.xmlMode || "" !== value ? "".concat(key, '="').concat(encode(value), '"') : key;
          })).join(" ");
        }
      }(elem.attribs, opts);
      attribs && (tag += " ".concat(attribs));
      0 === elem.children.length && (opts.xmlMode ? !1 !== opts.selfClosingTags : opts.selfClosingTags && singleTag.has(elem.name)) ? (opts.xmlMode || (tag += " "), 
      tag += "/>") : (tag += ">", elem.children.length > 0 && (tag += render(elem.children, opts)), 
      !opts.xmlMode && singleTag.has(elem.name) || (tag += "</".concat(elem.name, ">")));
      return tag;
    }(node, options);

   case ElementType.Text:
    return function(elem, opts) {
      var _a, data = elem.data || "";
      !1 === (null !== (_a = opts.encodeEntities) && void 0 !== _a ? _a : opts.decodeEntities) || !opts.xmlMode && elem.parent && unencodedElements.has(elem.parent.name) || (data = opts.xmlMode || "utf8" !== opts.encodeEntities ? (0, 
      entities_1.encodeXML)(data) : (0, entities_1.escapeText)(data));
      return data;
    }(node, options);
  }
}

lib$2.render = render, lib$2.default = render;

var foreignModeIntegrationPoints = new Set([ "mi", "mo", "mn", "ms", "mtext", "annotation-xml", "foreignObject", "desc", "title" ]), foreignElements = new Set([ "svg", "math" ]);

var __importDefault$3 = commonjsGlobal && commonjsGlobal.__importDefault || function(mod) {
  return mod && mod.__esModule ? mod : {
    "default": mod
  };
};

Object.defineProperty(stringify$1, "__esModule", {
  value: !0
}), stringify$1.innerText = stringify$1.textContent = stringify$1.getText = stringify$1.getInnerHTML = stringify$1.getOuterHTML = void 0;

var domhandler_1$3 = lib$4, dom_serializer_1 = __importDefault$3(lib$2), domelementtype_1 = lib$3;

function getOuterHTML(node, options) {
  return (0, dom_serializer_1.default)(node, options);
}

stringify$1.getOuterHTML = getOuterHTML, stringify$1.getInnerHTML = function(node, options) {
  return (0, domhandler_1$3.hasChildren)(node) ? node.children.map((function(node) {
    return getOuterHTML(node, options);
  })).join("") : "";
}, stringify$1.getText = function getText$1(node) {
  return Array.isArray(node) ? node.map(getText$1).join("") : (0, domhandler_1$3.isTag)(node) ? "br" === node.name ? "\n" : getText$1(node.children) : (0, 
  domhandler_1$3.isCDATA)(node) ? getText$1(node.children) : (0, domhandler_1$3.isText)(node) ? node.data : "";
}, stringify$1.textContent = function textContent(node) {
  return Array.isArray(node) ? node.map(textContent).join("") : (0, domhandler_1$3.hasChildren)(node) && !(0, 
  domhandler_1$3.isComment)(node) ? textContent(node.children) : (0, domhandler_1$3.isText)(node) ? node.data : "";
}, stringify$1.innerText = function innerText(node) {
  return Array.isArray(node) ? node.map(innerText).join("") : (0, domhandler_1$3.hasChildren)(node) && (node.type === domelementtype_1.ElementType.Tag || (0, 
  domhandler_1$3.isCDATA)(node)) ? innerText(node.children) : (0, domhandler_1$3.isText)(node) ? node.data : "";
};

var traversal = {};

Object.defineProperty(traversal, "__esModule", {
  value: !0
}), traversal.prevElementSibling = traversal.nextElementSibling = traversal.getName = traversal.hasAttrib = traversal.getAttributeValue = traversal.getSiblings = traversal.getParent = traversal.getChildren = void 0;

var domhandler_1$2 = lib$4;

function getChildren$1(elem) {
  return (0, domhandler_1$2.hasChildren)(elem) ? elem.children : [];
}

function getParent$1(elem) {
  return elem.parent || null;
}

traversal.getChildren = getChildren$1, traversal.getParent = getParent$1, traversal.getSiblings = function(elem) {
  var parent = getParent$1(elem);
  if (null != parent) return getChildren$1(parent);
  for (var siblings = [ elem ], prev = elem.prev, next = elem.next; null != prev; ) siblings.unshift(prev), 
  prev = prev.prev;
  for (;null != next; ) siblings.push(next), next = next.next;
  return siblings;
}, traversal.getAttributeValue = function(elem, name) {
  var _a;
  return null === (_a = elem.attribs) || void 0 === _a ? void 0 : _a[name];
}, traversal.hasAttrib = function(elem, name) {
  return null != elem.attribs && Object.prototype.hasOwnProperty.call(elem.attribs, name) && null != elem.attribs[name];
}, traversal.getName = function(elem) {
  return elem.name;
}, traversal.nextElementSibling = function(elem) {
  for (var next = elem.next; null !== next && !(0, domhandler_1$2.isTag)(next); ) next = next.next;
  return next;
}, traversal.prevElementSibling = function(elem) {
  for (var prev = elem.prev; null !== prev && !(0, domhandler_1$2.isTag)(prev); ) prev = prev.prev;
  return prev;
};

var manipulation = {};

function removeElement(elem) {
  if (elem.prev && (elem.prev.next = elem.next), elem.next && (elem.next.prev = elem.prev), 
  elem.parent) {
    var childs = elem.parent.children, childsIndex = childs.lastIndexOf(elem);
    childsIndex >= 0 && childs.splice(childsIndex, 1);
  }
  elem.next = null, elem.prev = null, elem.parent = null;
}

Object.defineProperty(manipulation, "__esModule", {
  value: !0
}), manipulation.prepend = manipulation.prependChild = manipulation.append = manipulation.appendChild = manipulation.replaceElement = manipulation.removeElement = void 0, 
manipulation.removeElement = removeElement, manipulation.replaceElement = function(elem, replacement) {
  var prev = replacement.prev = elem.prev;
  prev && (prev.next = replacement);
  var next = replacement.next = elem.next;
  next && (next.prev = replacement);
  var parent = replacement.parent = elem.parent;
  if (parent) {
    var childs = parent.children;
    childs[childs.lastIndexOf(elem)] = replacement, elem.parent = null;
  }
}, manipulation.appendChild = function(parent, child) {
  if (removeElement(child), child.next = null, child.parent = parent, parent.children.push(child) > 1) {
    var sibling = parent.children[parent.children.length - 2];
    sibling.next = child, child.prev = sibling;
  } else child.prev = null;
}, manipulation.append = function(elem, next) {
  removeElement(next);
  var parent = elem.parent, currNext = elem.next;
  if (next.next = currNext, next.prev = elem, elem.next = next, next.parent = parent, 
  currNext) {
    if (currNext.prev = next, parent) {
      var childs = parent.children;
      childs.splice(childs.lastIndexOf(currNext), 0, next);
    }
  } else parent && parent.children.push(next);
}, manipulation.prependChild = function(parent, child) {
  if (removeElement(child), child.parent = parent, child.prev = null, 1 !== parent.children.unshift(child)) {
    var sibling = parent.children[1];
    sibling.prev = child, child.next = sibling;
  } else child.next = null;
}, manipulation.prepend = function(elem, prev) {
  removeElement(prev);
  var parent = elem.parent;
  if (parent) {
    var childs = parent.children;
    childs.splice(childs.indexOf(elem), 0, prev);
  }
  elem.prev && (elem.prev.next = prev), prev.parent = parent, prev.prev = elem.prev, 
  prev.next = elem, elem.prev = prev;
};

var querying = {};

Object.defineProperty(querying, "__esModule", {
  value: !0
}), querying.findAll = querying.existsOne = querying.findOne = querying.findOneChild = querying.find = querying.filter = void 0;

var domhandler_1$1 = lib$4;

function find$3(test, nodes, recurse, limit) {
  for (var result = [], nodeStack = [ nodes ], indexStack = [ 0 ]; ;) if (indexStack[0] >= nodeStack[0].length) {
    if (1 === indexStack.length) return result;
    nodeStack.shift(), indexStack.shift();
  } else {
    var elem = nodeStack[0][indexStack[0]++];
    if (test(elem) && (result.push(elem), --limit <= 0)) return result;
    recurse && (0, domhandler_1$1.hasChildren)(elem) && elem.children.length > 0 && (indexStack.unshift(0), 
    nodeStack.unshift(elem.children));
  }
}

querying.filter = function(test, node, recurse, limit) {
  return void 0 === recurse && (recurse = !0), void 0 === limit && (limit = 1 / 0), 
  find$3(test, Array.isArray(node) ? node : [ node ], recurse, limit);
}, querying.find = find$3, querying.findOneChild = function(test, nodes) {
  return nodes.find(test);
}, querying.findOne = function findOne$1(test, nodes, recurse) {
  void 0 === recurse && (recurse = !0);
  for (var elem = null, i = 0; i < nodes.length && !elem; i++) {
    var node = nodes[i];
    (0, domhandler_1$1.isTag)(node) && (test(node) ? elem = node : recurse && node.children.length > 0 && (elem = findOne$1(test, node.children, !0)));
  }
  return elem;
}, querying.existsOne = function existsOne$1(test, nodes) {
  return nodes.some((function(checked) {
    return (0, domhandler_1$1.isTag)(checked) && (test(checked) || existsOne$1(test, checked.children));
  }));
}, querying.findAll = function(test, nodes) {
  for (var result = [], nodeStack = [ nodes ], indexStack = [ 0 ]; ;) if (indexStack[0] >= nodeStack[0].length) {
    if (1 === nodeStack.length) return result;
    nodeStack.shift(), indexStack.shift();
  } else {
    var elem = nodeStack[0][indexStack[0]++];
    (0, domhandler_1$1.isTag)(elem) && (test(elem) && result.push(elem), elem.children.length > 0 && (indexStack.unshift(0), 
    nodeStack.unshift(elem.children)));
  }
};

var legacy = {};

Object.defineProperty(legacy, "__esModule", {
  value: !0
}), legacy.getElementsByTagType = legacy.getElementsByTagName = legacy.getElementById = legacy.getElements = legacy.testElement = void 0;

var domhandler_1 = lib$4, querying_js_1 = querying, Checks = {
  tag_name: function(name) {
    return "function" == typeof name ? function(elem) {
      return (0, domhandler_1.isTag)(elem) && name(elem.name);
    } : "*" === name ? domhandler_1.isTag : function(elem) {
      return (0, domhandler_1.isTag)(elem) && elem.name === name;
    };
  },
  tag_type: function(type) {
    return "function" == typeof type ? function(elem) {
      return type(elem.type);
    } : function(elem) {
      return elem.type === type;
    };
  },
  tag_contains: function(data) {
    return "function" == typeof data ? function(elem) {
      return (0, domhandler_1.isText)(elem) && data(elem.data);
    } : function(elem) {
      return (0, domhandler_1.isText)(elem) && elem.data === data;
    };
  }
};

function getAttribCheck(attrib, value) {
  return "function" == typeof value ? function(elem) {
    return (0, domhandler_1.isTag)(elem) && value(elem.attribs[attrib]);
  } : function(elem) {
    return (0, domhandler_1.isTag)(elem) && elem.attribs[attrib] === value;
  };
}

function combineFuncs(a, b) {
  return function(elem) {
    return a(elem) || b(elem);
  };
}

function compileTest(options) {
  var funcs = Object.keys(options).map((function(key) {
    var value = options[key];
    return Object.prototype.hasOwnProperty.call(Checks, key) ? Checks[key](value) : getAttribCheck(key, value);
  }));
  return 0 === funcs.length ? null : funcs.reduce(combineFuncs);
}

legacy.testElement = function(options, node) {
  var test = compileTest(options);
  return !test || test(node);
}, legacy.getElements = function(options, nodes, recurse, limit) {
  void 0 === limit && (limit = 1 / 0);
  var test = compileTest(options);
  return test ? (0, querying_js_1.filter)(test, nodes, recurse, limit) : [];
}, legacy.getElementById = function(id, nodes, recurse) {
  return void 0 === recurse && (recurse = !0), Array.isArray(nodes) || (nodes = [ nodes ]), 
  (0, querying_js_1.findOne)(getAttribCheck("id", id), nodes, recurse);
}, legacy.getElementsByTagName = function(tagName, nodes, recurse, limit) {
  return void 0 === recurse && (recurse = !0), void 0 === limit && (limit = 1 / 0), 
  (0, querying_js_1.filter)(Checks.tag_name(tagName), nodes, recurse, limit);
}, legacy.getElementsByTagType = function(type, nodes, recurse, limit) {
  return void 0 === recurse && (recurse = !0), void 0 === limit && (limit = 1 / 0), 
  (0, querying_js_1.filter)(Checks.tag_type(type), nodes, recurse, limit);
};

var helpers = {};

!function(exports) {
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.uniqueSort = exports.compareDocumentPosition = exports.DocumentPosition = exports.removeSubsets = void 0;
  var DocumentPosition, domhandler_1 = lib$4;
  function compareDocumentPosition(nodeA, nodeB) {
    var aParents = [], bParents = [];
    if (nodeA === nodeB) return 0;
    for (var current = (0, domhandler_1.hasChildren)(nodeA) ? nodeA : nodeA.parent; current; ) aParents.unshift(current), 
    current = current.parent;
    for (current = (0, domhandler_1.hasChildren)(nodeB) ? nodeB : nodeB.parent; current; ) bParents.unshift(current), 
    current = current.parent;
    for (var maxIdx = Math.min(aParents.length, bParents.length), idx = 0; idx < maxIdx && aParents[idx] === bParents[idx]; ) idx++;
    if (0 === idx) return DocumentPosition.DISCONNECTED;
    var sharedParent = aParents[idx - 1], siblings = sharedParent.children, aSibling = aParents[idx], bSibling = bParents[idx];
    return siblings.indexOf(aSibling) > siblings.indexOf(bSibling) ? sharedParent === nodeB ? DocumentPosition.FOLLOWING | DocumentPosition.CONTAINED_BY : DocumentPosition.FOLLOWING : sharedParent === nodeA ? DocumentPosition.PRECEDING | DocumentPosition.CONTAINS : DocumentPosition.PRECEDING;
  }
  exports.removeSubsets = function(nodes) {
    for (var idx = nodes.length; --idx >= 0; ) {
      var node = nodes[idx];
      if (idx > 0 && nodes.lastIndexOf(node, idx - 1) >= 0) nodes.splice(idx, 1); else for (var ancestor = node.parent; ancestor; ancestor = ancestor.parent) if (nodes.includes(ancestor)) {
        nodes.splice(idx, 1);
        break;
      }
    }
    return nodes;
  }, function(DocumentPosition) {
    DocumentPosition[DocumentPosition.DISCONNECTED = 1] = "DISCONNECTED", DocumentPosition[DocumentPosition.PRECEDING = 2] = "PRECEDING", 
    DocumentPosition[DocumentPosition.FOLLOWING = 4] = "FOLLOWING", DocumentPosition[DocumentPosition.CONTAINS = 8] = "CONTAINS", 
    DocumentPosition[DocumentPosition.CONTAINED_BY = 16] = "CONTAINED_BY";
  }(DocumentPosition = exports.DocumentPosition || (exports.DocumentPosition = {})), 
  exports.compareDocumentPosition = compareDocumentPosition, exports.uniqueSort = function(nodes) {
    return nodes = nodes.filter((function(node, i, arr) {
      return !arr.includes(node, i + 1);
    })), nodes.sort((function(a, b) {
      var relative = compareDocumentPosition(a, b);
      return relative & DocumentPosition.PRECEDING ? -1 : relative & DocumentPosition.FOLLOWING ? 1 : 0;
    })), nodes;
  };
}(helpers);

var feeds = {};

Object.defineProperty(feeds, "__esModule", {
  value: !0
}), feeds.getFeed = void 0;

var stringify_js_1 = stringify$1, legacy_js_1 = legacy;

feeds.getFeed = function(doc) {
  var feedRoot = getOneElement(isValidFeed, doc);
  return feedRoot ? "feed" === feedRoot.name ? function(feedRoot) {
    var _a, childs = feedRoot.children, feed = {
      type: "atom",
      items: (0, legacy_js_1.getElementsByTagName)("entry", childs).map((function(item) {
        var _a, children = item.children, entry = {
          media: getMediaElements(children)
        };
        addConditionally(entry, "id", "id", children), addConditionally(entry, "title", "title", children);
        var href = null === (_a = getOneElement("link", children)) || void 0 === _a ? void 0 : _a.attribs.href;
        href && (entry.link = href);
        var description = fetch("summary", children) || fetch("content", children);
        description && (entry.description = description);
        var pubDate = fetch("updated", children);
        return pubDate && (entry.pubDate = new Date(pubDate)), entry;
      }))
    };
    addConditionally(feed, "id", "id", childs), addConditionally(feed, "title", "title", childs);
    var href = null === (_a = getOneElement("link", childs)) || void 0 === _a ? void 0 : _a.attribs.href;
    href && (feed.link = href);
    addConditionally(feed, "description", "subtitle", childs);
    var updated = fetch("updated", childs);
    updated && (feed.updated = new Date(updated));
    return addConditionally(feed, "author", "email", childs, !0), feed;
  }(feedRoot) : function(feedRoot) {
    var _a, _b, childs = null !== (_b = null === (_a = getOneElement("channel", feedRoot.children)) || void 0 === _a ? void 0 : _a.children) && void 0 !== _b ? _b : [], feed = {
      type: feedRoot.name.substr(0, 3),
      id: "",
      items: (0, legacy_js_1.getElementsByTagName)("item", feedRoot.children).map((function(item) {
        var children = item.children, entry = {
          media: getMediaElements(children)
        };
        addConditionally(entry, "id", "guid", children), addConditionally(entry, "title", "title", children), 
        addConditionally(entry, "link", "link", children), addConditionally(entry, "description", "description", children);
        var pubDate = fetch("pubDate", children) || fetch("dc:date", children);
        return pubDate && (entry.pubDate = new Date(pubDate)), entry;
      }))
    };
    addConditionally(feed, "title", "title", childs), addConditionally(feed, "link", "link", childs), 
    addConditionally(feed, "description", "description", childs);
    var updated = fetch("lastBuildDate", childs);
    updated && (feed.updated = new Date(updated));
    return addConditionally(feed, "author", "managingEditor", childs, !0), feed;
  }(feedRoot) : null;
};

var MEDIA_KEYS_STRING = [ "url", "type", "lang" ], MEDIA_KEYS_INT = [ "fileSize", "bitrate", "framerate", "samplingrate", "channels", "duration", "height", "width" ];

function getMediaElements(where) {
  return (0, legacy_js_1.getElementsByTagName)("media:content", where).map((function(elem) {
    for (var attribs = elem.attribs, media = {
      medium: attribs.medium,
      isDefault: !!attribs.isDefault
    }, _i = 0, MEDIA_KEYS_STRING_1 = MEDIA_KEYS_STRING; _i < MEDIA_KEYS_STRING_1.length; _i++) {
      attribs[attrib = MEDIA_KEYS_STRING_1[_i]] && (media[attrib] = attribs[attrib]);
    }
    for (var _a = 0, MEDIA_KEYS_INT_1 = MEDIA_KEYS_INT; _a < MEDIA_KEYS_INT_1.length; _a++) {
      var attrib;
      attribs[attrib = MEDIA_KEYS_INT_1[_a]] && (media[attrib] = parseInt(attribs[attrib], 10));
    }
    return attribs.expression && (media.expression = attribs.expression), media;
  }));
}

function getOneElement(tagName, node) {
  return (0, legacy_js_1.getElementsByTagName)(tagName, node, !0, 1)[0];
}

function fetch(tagName, where, recurse) {
  return void 0 === recurse && (recurse = !1), (0, stringify_js_1.textContent)((0, 
  legacy_js_1.getElementsByTagName)(tagName, where, recurse, 1)).trim();
}

function addConditionally(obj, prop, tagName, where, recurse) {
  void 0 === recurse && (recurse = !1);
  var val = fetch(tagName, where, recurse);
  val && (obj[prop] = val);
}

function isValidFeed(value) {
  return "rss" === value || "feed" === value || "rdf:RDF" === value;
}

!function(exports) {
  var __createBinding = commonjsGlobal && commonjsGlobal.__createBinding || (Object.create ? function(o, m, k, k2) {
    void 0 === k2 && (k2 = k);
    var desc = Object.getOwnPropertyDescriptor(m, k);
    desc && !("get" in desc ? !m.__esModule : desc.writable || desc.configurable) || (desc = {
      enumerable: !0,
      get: function() {
        return m[k];
      }
    }), Object.defineProperty(o, k2, desc);
  } : function(o, m, k, k2) {
    void 0 === k2 && (k2 = k), o[k2] = m[k];
  }), __exportStar = commonjsGlobal && commonjsGlobal.__exportStar || function(m, exports) {
    for (var p in m) "default" === p || Object.prototype.hasOwnProperty.call(exports, p) || __createBinding(exports, m, p);
  };
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.hasChildren = exports.isDocument = exports.isComment = exports.isText = exports.isCDATA = exports.isTag = void 0, 
  __exportStar(stringify$1, exports), __exportStar(traversal, exports), __exportStar(manipulation, exports), 
  __exportStar(querying, exports), __exportStar(legacy, exports), __exportStar(helpers, exports), 
  __exportStar(feeds, exports);
  var domhandler_1 = lib$4;
  Object.defineProperty(exports, "isTag", {
    enumerable: !0,
    get: function() {
      return domhandler_1.isTag;
    }
  }), Object.defineProperty(exports, "isCDATA", {
    enumerable: !0,
    get: function() {
      return domhandler_1.isCDATA;
    }
  }), Object.defineProperty(exports, "isText", {
    enumerable: !0,
    get: function() {
      return domhandler_1.isText;
    }
  }), Object.defineProperty(exports, "isComment", {
    enumerable: !0,
    get: function() {
      return domhandler_1.isComment;
    }
  }), Object.defineProperty(exports, "isDocument", {
    enumerable: !0,
    get: function() {
      return domhandler_1.isDocument;
    }
  }), Object.defineProperty(exports, "hasChildren", {
    enumerable: !0,
    get: function() {
      return domhandler_1.hasChildren;
    }
  });
}(lib$5);

var SelectorType, boolbase = {
  trueFunc: function() {
    return !0;
  },
  falseFunc: function() {
    return !1;
  }
}, compile$3 = {};

!function(SelectorType) {
  SelectorType.Attribute = "attribute", SelectorType.Pseudo = "pseudo", SelectorType.PseudoElement = "pseudo-element", 
  SelectorType.Tag = "tag", SelectorType.Universal = "universal", SelectorType.Adjacent = "adjacent", 
  SelectorType.Child = "child", SelectorType.Descendant = "descendant", SelectorType.Parent = "parent", 
  SelectorType.Sibling = "sibling", SelectorType.ColumnCombinator = "column-combinator";
}(SelectorType || (SelectorType = {}));

var AttributeAction;

!function(AttributeAction) {
  AttributeAction.Any = "any", AttributeAction.Element = "element", AttributeAction.End = "end", 
  AttributeAction.Equals = "equals", AttributeAction.Exists = "exists", AttributeAction.Hyphen = "hyphen", 
  AttributeAction.Not = "not", AttributeAction.Start = "start";
}(AttributeAction || (AttributeAction = {}));

const reName = /^[^\\#]?(?:\\(?:[\da-f]{1,6}\s?|.)|[\w\-\u00b0-\uFFFF])+/, reEscape = /\\([\da-f]{1,6}\s?|(\s)|.)/gi, actionTypes = new Map([ [ 126, AttributeAction.Element ], [ 94, AttributeAction.Start ], [ 36, AttributeAction.End ], [ 42, AttributeAction.Any ], [ 33, AttributeAction.Not ], [ 124, AttributeAction.Hyphen ] ]), unpackPseudos = new Set([ "has", "not", "matches", "is", "where", "host", "host-context" ]);

function isTraversal$1(selector) {
  switch (selector.type) {
   case SelectorType.Adjacent:
   case SelectorType.Child:
   case SelectorType.Descendant:
   case SelectorType.Parent:
   case SelectorType.Sibling:
   case SelectorType.ColumnCombinator:
    return !0;

   default:
    return !1;
  }
}

const stripQuotesFromPseudos = new Set([ "contains", "icontains" ]);

function funescape(_, escaped, escapedWhitespace) {
  const high = parseInt(escaped, 16) - 0x10000;
  return high != high || escapedWhitespace ? escaped : high < 0 ? String.fromCharCode(high + 0x10000) : String.fromCharCode(high >> 10 | 0xd800, 0x3ff & high | 0xdc00);
}

function unescapeCSS(str) {
  return str.replace(reEscape, funescape);
}

function isQuote(c) {
  return 39 === c || 34 === c;
}

function isWhitespace(c) {
  return 32 === c || 9 === c || 10 === c || 12 === c || 13 === c;
}

function parseSelector(subselects, selector, selectorIndex) {
  let tokens = [];
  function getName(offset) {
    const match = selector.slice(selectorIndex + offset).match(reName);
    if (!match) throw new Error(`Expected name, found ${selector.slice(selectorIndex)}`);
    const [name] = match;
    return selectorIndex += offset + name.length, unescapeCSS(name);
  }
  function stripWhitespace(offset) {
    for (selectorIndex += offset; selectorIndex < selector.length && isWhitespace(selector.charCodeAt(selectorIndex)); ) selectorIndex++;
  }
  function readValueWithParenthesis() {
    const start = selectorIndex += 1;
    let counter = 1;
    for (;counter > 0 && selectorIndex < selector.length; selectorIndex++) 40 !== selector.charCodeAt(selectorIndex) || isEscaped(selectorIndex) ? 41 !== selector.charCodeAt(selectorIndex) || isEscaped(selectorIndex) || counter-- : counter++;
    if (counter) throw new Error("Parenthesis not matched");
    return unescapeCSS(selector.slice(start, selectorIndex - 1));
  }
  function isEscaped(pos) {
    let slashCount = 0;
    for (;92 === selector.charCodeAt(--pos); ) slashCount++;
    return 1 == (1 & slashCount);
  }
  function ensureNotTraversal() {
    if (tokens.length > 0 && isTraversal$1(tokens[tokens.length - 1])) throw new Error("Did not expect successive traversals.");
  }
  function addTraversal(type) {
    tokens.length > 0 && tokens[tokens.length - 1].type === SelectorType.Descendant ? tokens[tokens.length - 1].type = type : (ensureNotTraversal(), 
    tokens.push({
      type
    }));
  }
  function addSpecialAttribute(name, action) {
    tokens.push({
      type: SelectorType.Attribute,
      name,
      action,
      value: getName(1),
      namespace: null,
      ignoreCase: "quirks"
    });
  }
  function finalizeSubselector() {
    if (tokens.length && tokens[tokens.length - 1].type === SelectorType.Descendant && tokens.pop(), 
    0 === tokens.length) throw new Error("Empty sub-selector");
    subselects.push(tokens);
  }
  if (stripWhitespace(0), selector.length === selectorIndex) return selectorIndex;
  loop: for (;selectorIndex < selector.length; ) {
    const firstChar = selector.charCodeAt(selectorIndex);
    switch (firstChar) {
     case 32:
     case 9:
     case 10:
     case 12:
     case 13:
      0 !== tokens.length && tokens[0].type === SelectorType.Descendant || (ensureNotTraversal(), 
      tokens.push({
        type: SelectorType.Descendant
      })), stripWhitespace(1);
      break;

     case 62:
      addTraversal(SelectorType.Child), stripWhitespace(1);
      break;

     case 60:
      addTraversal(SelectorType.Parent), stripWhitespace(1);
      break;

     case 126:
      addTraversal(SelectorType.Sibling), stripWhitespace(1);
      break;

     case 43:
      addTraversal(SelectorType.Adjacent), stripWhitespace(1);
      break;

     case 46:
      addSpecialAttribute("class", AttributeAction.Element);
      break;

     case 35:
      addSpecialAttribute("id", AttributeAction.Equals);
      break;

     case 91:
      {
        let name;
        stripWhitespace(1);
        let namespace = null;
        124 === selector.charCodeAt(selectorIndex) ? name = getName(1) : selector.startsWith("*|", selectorIndex) ? (namespace = "*", 
        name = getName(2)) : (name = getName(0), 124 === selector.charCodeAt(selectorIndex) && 61 !== selector.charCodeAt(selectorIndex + 1) && (namespace = name, 
        name = getName(1))), stripWhitespace(0);
        let action = AttributeAction.Exists;
        const possibleAction = actionTypes.get(selector.charCodeAt(selectorIndex));
        if (possibleAction) {
          if (action = possibleAction, 61 !== selector.charCodeAt(selectorIndex + 1)) throw new Error("Expected `=`");
          stripWhitespace(2);
        } else 61 === selector.charCodeAt(selectorIndex) && (action = AttributeAction.Equals, 
        stripWhitespace(1));
        let value = "", ignoreCase = null;
        if ("exists" !== action) {
          if (isQuote(selector.charCodeAt(selectorIndex))) {
            const quote = selector.charCodeAt(selectorIndex);
            let sectionEnd = selectorIndex + 1;
            for (;sectionEnd < selector.length && (selector.charCodeAt(sectionEnd) !== quote || isEscaped(sectionEnd)); ) sectionEnd += 1;
            if (selector.charCodeAt(sectionEnd) !== quote) throw new Error("Attribute value didn't end");
            value = unescapeCSS(selector.slice(selectorIndex + 1, sectionEnd)), selectorIndex = sectionEnd + 1;
          } else {
            const valueStart = selectorIndex;
            for (;selectorIndex < selector.length && (!isWhitespace(selector.charCodeAt(selectorIndex)) && 93 !== selector.charCodeAt(selectorIndex) || isEscaped(selectorIndex)); ) selectorIndex += 1;
            value = unescapeCSS(selector.slice(valueStart, selectorIndex));
          }
          stripWhitespace(0);
          const forceIgnore = 0x20 | selector.charCodeAt(selectorIndex);
          115 === forceIgnore ? (ignoreCase = !1, stripWhitespace(1)) : 105 === forceIgnore && (ignoreCase = !0, 
          stripWhitespace(1));
        }
        if (93 !== selector.charCodeAt(selectorIndex)) throw new Error("Attribute selector didn't terminate");
        selectorIndex += 1;
        const attributeSelector = {
          type: SelectorType.Attribute,
          name,
          action,
          value,
          namespace,
          ignoreCase
        };
        tokens.push(attributeSelector);
        break;
      }

     case 58:
      {
        if (58 === selector.charCodeAt(selectorIndex + 1)) {
          tokens.push({
            type: SelectorType.PseudoElement,
            name: getName(2).toLowerCase(),
            data: 40 === selector.charCodeAt(selectorIndex) ? readValueWithParenthesis() : null
          });
          continue;
        }
        const name = getName(1).toLowerCase();
        let data = null;
        if (40 === selector.charCodeAt(selectorIndex)) if (unpackPseudos.has(name)) {
          if (isQuote(selector.charCodeAt(selectorIndex + 1))) throw new Error(`Pseudo-selector ${name} cannot be quoted`);
          if (data = [], selectorIndex = parseSelector(data, selector, selectorIndex + 1), 
          41 !== selector.charCodeAt(selectorIndex)) throw new Error(`Missing closing parenthesis in :${name} (${selector})`);
          selectorIndex += 1;
        } else {
          if (data = readValueWithParenthesis(), stripQuotesFromPseudos.has(name)) {
            const quot = data.charCodeAt(0);
            quot === data.charCodeAt(data.length - 1) && isQuote(quot) && (data = data.slice(1, -1));
          }
          data = unescapeCSS(data);
        }
        tokens.push({
          type: SelectorType.Pseudo,
          name,
          data
        });
        break;
      }

     case 44:
      finalizeSubselector(), tokens = [], stripWhitespace(1);
      break;

     default:
      {
        if (selector.startsWith("/*", selectorIndex)) {
          const endIndex = selector.indexOf("*/", selectorIndex + 2);
          if (endIndex < 0) throw new Error("Comment was not terminated");
          selectorIndex = endIndex + 2, 0 === tokens.length && stripWhitespace(0);
          break;
        }
        let name, namespace = null;
        if (42 === firstChar) selectorIndex += 1, name = "*"; else if (124 === firstChar) {
          if (name = "", 124 === selector.charCodeAt(selectorIndex + 1)) {
            addTraversal(SelectorType.ColumnCombinator), stripWhitespace(2);
            break;
          }
        } else {
          if (!reName.test(selector.slice(selectorIndex))) break loop;
          name = getName(0);
        }
        124 === selector.charCodeAt(selectorIndex) && 124 !== selector.charCodeAt(selectorIndex + 1) && (namespace = name, 
        42 === selector.charCodeAt(selectorIndex + 1) ? (name = "*", selectorIndex += 2) : name = getName(1)), 
        tokens.push("*" === name ? {
          type: SelectorType.Universal,
          namespace
        } : {
          type: SelectorType.Tag,
          name,
          namespace
        });
      }
    }
  }
  return finalizeSubselector(), selectorIndex;
}

const attribValChars = [ "\\", '"' ], pseudoValChars = [ ...attribValChars, "(", ")" ], charsToEscapeInAttributeValue = new Set(attribValChars.map((c => c.charCodeAt(0)))), charsToEscapeInPseudoValue = new Set(pseudoValChars.map((c => c.charCodeAt(0)))), charsToEscapeInName = new Set([ ...pseudoValChars, "~", "^", "$", "*", "+", "!", "|", ":", "[", "]", " ", "." ].map((c => c.charCodeAt(0))));

function stringify(selector) {
  return selector.map((token => token.map(stringifyToken).join(""))).join(", ");
}

function stringifyToken(token, index, arr) {
  switch (token.type) {
   case SelectorType.Child:
    return 0 === index ? "> " : " > ";

   case SelectorType.Parent:
    return 0 === index ? "< " : " < ";

   case SelectorType.Sibling:
    return 0 === index ? "~ " : " ~ ";

   case SelectorType.Adjacent:
    return 0 === index ? "+ " : " + ";

   case SelectorType.Descendant:
    return " ";

   case SelectorType.ColumnCombinator:
    return 0 === index ? "|| " : " || ";

   case SelectorType.Universal:
    return "*" === token.namespace && index + 1 < arr.length && "name" in arr[index + 1] ? "" : `${getNamespace(token.namespace)}*`;

   case SelectorType.Tag:
    return getNamespacedName(token);

   case SelectorType.PseudoElement:
    return `::${escapeName(token.name, charsToEscapeInName)}${null === token.data ? "" : `(${escapeName(token.data, charsToEscapeInPseudoValue)})`}`;

   case SelectorType.Pseudo:
    return `:${escapeName(token.name, charsToEscapeInName)}${null === token.data ? "" : `(${"string" == typeof token.data ? escapeName(token.data, charsToEscapeInPseudoValue) : stringify(token.data)})`}`;

   case SelectorType.Attribute:
    {
      if ("id" === token.name && token.action === AttributeAction.Equals && "quirks" === token.ignoreCase && !token.namespace) return `#${escapeName(token.value, charsToEscapeInName)}`;
      if ("class" === token.name && token.action === AttributeAction.Element && "quirks" === token.ignoreCase && !token.namespace) return `.${escapeName(token.value, charsToEscapeInName)}`;
      const name = getNamespacedName(token);
      return token.action === AttributeAction.Exists ? `[${name}]` : `[${name}${function(action) {
        switch (action) {
         case AttributeAction.Equals:
          return "";

         case AttributeAction.Element:
          return "~";

         case AttributeAction.Start:
          return "^";

         case AttributeAction.End:
          return "$";

         case AttributeAction.Any:
          return "*";

         case AttributeAction.Not:
          return "!";

         case AttributeAction.Hyphen:
          return "|";

         case AttributeAction.Exists:
          throw new Error("Shouldn't be here");
        }
      }(token.action)}="${escapeName(token.value, charsToEscapeInAttributeValue)}"${null === token.ignoreCase ? "" : token.ignoreCase ? " i" : " s"}]`;
    }
  }
}

function getNamespacedName(token) {
  return `${getNamespace(token.namespace)}${escapeName(token.name, charsToEscapeInName)}`;
}

function getNamespace(namespace) {
  return null !== namespace ? `${"*" === namespace ? "*" : escapeName(namespace, charsToEscapeInName)}|` : "";
}

function escapeName(str, charsToEscape) {
  let lastIdx = 0, ret = "";
  for (let i = 0; i < str.length; i++) charsToEscape.has(str.charCodeAt(i)) && (ret += `${str.slice(lastIdx, i)}\\${str.charAt(i)}`, 
  lastIdx = i + 1);
  return ret.length > 0 ? ret + str.slice(lastIdx) : str;
}

var es = Object.freeze({
  __proto__: null,
  get AttributeAction() {
    return AttributeAction;
  },
  IgnoreCaseMode: {
    Unknown: null,
    QuirksMode: "quirks",
    IgnoreCase: !0,
    CaseSensitive: !1
  },
  get SelectorType() {
    return SelectorType;
  },
  isTraversal: isTraversal$1,
  parse: function(selector) {
    const subselects = [], endIndex = parseSelector(subselects, `${selector}`, 0);
    if (endIndex < selector.length) throw new Error(`Unmatched selector: ${selector.slice(endIndex)}`);
    return subselects;
  },
  stringify
}), require$$0 = getAugmentedNamespace(es), sort = {};

Object.defineProperty(sort, "__esModule", {
  value: !0
}), sort.isTraversal = void 0;

var css_what_1$2 = require$$0, procedure = new Map([ [ css_what_1$2.SelectorType.Universal, 50 ], [ css_what_1$2.SelectorType.Tag, 30 ], [ css_what_1$2.SelectorType.Attribute, 1 ], [ css_what_1$2.SelectorType.Pseudo, 0 ] ]);

sort.isTraversal = function(token) {
  return !procedure.has(token.type);
};

var attributes$1 = new Map([ [ css_what_1$2.AttributeAction.Exists, 10 ], [ css_what_1$2.AttributeAction.Equals, 8 ], [ css_what_1$2.AttributeAction.Not, 7 ], [ css_what_1$2.AttributeAction.Start, 6 ], [ css_what_1$2.AttributeAction.End, 6 ], [ css_what_1$2.AttributeAction.Any, 5 ] ]);

function getProcedure(token) {
  var _a, _b, proc = null !== (_a = procedure.get(token.type)) && void 0 !== _a ? _a : -1;
  return token.type === css_what_1$2.SelectorType.Attribute ? (proc = null !== (_b = attributes$1.get(token.action)) && void 0 !== _b ? _b : 4, 
  token.action === css_what_1$2.AttributeAction.Equals && "id" === token.name && (proc = 9), 
  token.ignoreCase && (proc >>= 1)) : token.type === css_what_1$2.SelectorType.Pseudo && (token.data ? "has" === token.name || "contains" === token.name ? proc = 0 : Array.isArray(token.data) ? (proc = Math.min.apply(Math, token.data.map((function(d) {
    return Math.min.apply(Math, d.map(getProcedure));
  })))) < 0 && (proc = 0) : proc = 2 : proc = 3), proc;
}

sort.default = function(arr) {
  for (var procs = arr.map(getProcedure), i = 1; i < arr.length; i++) {
    var procNew = procs[i];
    if (!(procNew < 0)) for (var j = i - 1; j >= 0 && procNew < procs[j]; j--) {
      var token = arr[j + 1];
      arr[j + 1] = arr[j], arr[j] = token, procs[j + 1] = procs[j], procs[j] = procNew;
    }
  }
};

var general = {}, attributes = {}, __importDefault$2 = commonjsGlobal && commonjsGlobal.__importDefault || function(mod) {
  return mod && mod.__esModule ? mod : {
    "default": mod
  };
};

Object.defineProperty(attributes, "__esModule", {
  value: !0
}), attributes.attributeRules = void 0;

var boolbase_1$2 = __importDefault$2(boolbase), reChars = /[-[\]{}()*+?.,\\^$|#\s]/g;

function escapeRegex(value) {
  return value.replace(reChars, "\\$&");
}

var caseInsensitiveAttributes = new Set([ "accept", "accept-charset", "align", "alink", "axis", "bgcolor", "charset", "checked", "clear", "codetype", "color", "compact", "declare", "defer", "dir", "direction", "disabled", "enctype", "face", "frame", "hreflang", "http-equiv", "lang", "language", "link", "media", "method", "multiple", "nohref", "noresize", "noshade", "nowrap", "readonly", "rel", "rev", "rules", "scope", "scrolling", "selected", "shape", "target", "text", "type", "valign", "valuetype", "vlink" ]);

function shouldIgnoreCase(selector, options) {
  return "boolean" == typeof selector.ignoreCase ? selector.ignoreCase : "quirks" === selector.ignoreCase ? !!options.quirksMode : !options.xmlMode && caseInsensitiveAttributes.has(selector.name);
}

attributes.attributeRules = {
  equals: function(next, data, options) {
    var adapter = options.adapter, name = data.name, value = data.value;
    return shouldIgnoreCase(data, options) ? (value = value.toLowerCase(), function(elem) {
      var attr = adapter.getAttributeValue(elem, name);
      return null != attr && attr.length === value.length && attr.toLowerCase() === value && next(elem);
    }) : function(elem) {
      return adapter.getAttributeValue(elem, name) === value && next(elem);
    };
  },
  hyphen: function(next, data, options) {
    var adapter = options.adapter, name = data.name, value = data.value, len = value.length;
    return shouldIgnoreCase(data, options) ? (value = value.toLowerCase(), function(elem) {
      var attr = adapter.getAttributeValue(elem, name);
      return null != attr && (attr.length === len || "-" === attr.charAt(len)) && attr.substr(0, len).toLowerCase() === value && next(elem);
    }) : function(elem) {
      var attr = adapter.getAttributeValue(elem, name);
      return null != attr && (attr.length === len || "-" === attr.charAt(len)) && attr.substr(0, len) === value && next(elem);
    };
  },
  element: function(next, data, options) {
    var adapter = options.adapter, name = data.name, value = data.value;
    if (/\s/.test(value)) return boolbase_1$2.default.falseFunc;
    var regex = new RegExp("(?:^|\\s)".concat(escapeRegex(value), "(?:$|\\s)"), shouldIgnoreCase(data, options) ? "i" : "");
    return function(elem) {
      var attr = adapter.getAttributeValue(elem, name);
      return null != attr && attr.length >= value.length && regex.test(attr) && next(elem);
    };
  },
  exists: function(next, _a, _b) {
    var name = _a.name, adapter = _b.adapter;
    return function(elem) {
      return adapter.hasAttrib(elem, name) && next(elem);
    };
  },
  start: function(next, data, options) {
    var adapter = options.adapter, name = data.name, value = data.value, len = value.length;
    return 0 === len ? boolbase_1$2.default.falseFunc : shouldIgnoreCase(data, options) ? (value = value.toLowerCase(), 
    function(elem) {
      var attr = adapter.getAttributeValue(elem, name);
      return null != attr && attr.length >= len && attr.substr(0, len).toLowerCase() === value && next(elem);
    }) : function(elem) {
      var _a;
      return !!(null === (_a = adapter.getAttributeValue(elem, name)) || void 0 === _a ? void 0 : _a.startsWith(value)) && next(elem);
    };
  },
  end: function(next, data, options) {
    var adapter = options.adapter, name = data.name, value = data.value, len = -value.length;
    return 0 === len ? boolbase_1$2.default.falseFunc : shouldIgnoreCase(data, options) ? (value = value.toLowerCase(), 
    function(elem) {
      var _a;
      return (null === (_a = adapter.getAttributeValue(elem, name)) || void 0 === _a ? void 0 : _a.substr(len).toLowerCase()) === value && next(elem);
    }) : function(elem) {
      var _a;
      return !!(null === (_a = adapter.getAttributeValue(elem, name)) || void 0 === _a ? void 0 : _a.endsWith(value)) && next(elem);
    };
  },
  any: function(next, data, options) {
    var adapter = options.adapter, name = data.name, value = data.value;
    if ("" === value) return boolbase_1$2.default.falseFunc;
    if (shouldIgnoreCase(data, options)) {
      var regex_1 = new RegExp(escapeRegex(value), "i");
      return function(elem) {
        var attr = adapter.getAttributeValue(elem, name);
        return null != attr && attr.length >= value.length && regex_1.test(attr) && next(elem);
      };
    }
    return function(elem) {
      var _a;
      return !!(null === (_a = adapter.getAttributeValue(elem, name)) || void 0 === _a ? void 0 : _a.includes(value)) && next(elem);
    };
  },
  not: function(next, data, options) {
    var adapter = options.adapter, name = data.name, value = data.value;
    return "" === value ? function(elem) {
      return !!adapter.getAttributeValue(elem, name) && next(elem);
    } : shouldIgnoreCase(data, options) ? (value = value.toLowerCase(), function(elem) {
      var attr = adapter.getAttributeValue(elem, name);
      return (null == attr || attr.length !== value.length || attr.toLowerCase() !== value) && next(elem);
    }) : function(elem) {
      return adapter.getAttributeValue(elem, name) !== value && next(elem);
    };
  }
};

var pseudoSelectors = {}, filters$1 = {}, lib = {}, parse$1w = {};

Object.defineProperty(parse$1w, "__esModule", {
  value: !0
}), parse$1w.parse = void 0;

var whitespace = new Set([ 9, 10, 12, 13, 32 ]), ZERO = "0".charCodeAt(0), NINE = "9".charCodeAt(0);

parse$1w.parse = function(formula) {
  if ("even" === (formula = formula.trim().toLowerCase())) return [ 2, 0 ];
  if ("odd" === formula) return [ 2, 1 ];
  var idx = 0, a = 0, sign = readSign(), number = readNumber();
  if (idx < formula.length && "n" === formula.charAt(idx) && (idx++, a = sign * (null != number ? number : 1), 
  skipWhitespace(), idx < formula.length ? (sign = readSign(), skipWhitespace(), number = readNumber()) : sign = number = 0), 
  null === number || idx < formula.length) throw new Error("n-th rule couldn't be parsed ('".concat(formula, "')"));
  return [ a, sign * number ];
  function readSign() {
    return "-" === formula.charAt(idx) ? (idx++, -1) : ("+" === formula.charAt(idx) && idx++, 
    1);
  }
  function readNumber() {
    for (var start = idx, value = 0; idx < formula.length && formula.charCodeAt(idx) >= ZERO && formula.charCodeAt(idx) <= NINE; ) value = 10 * value + (formula.charCodeAt(idx) - ZERO), 
    idx++;
    return idx === start ? null : value;
  }
  function skipWhitespace() {
    for (;idx < formula.length && whitespace.has(formula.charCodeAt(idx)); ) idx++;
  }
};

var compile$2 = {}, __importDefault$1 = commonjsGlobal && commonjsGlobal.__importDefault || function(mod) {
  return mod && mod.__esModule ? mod : {
    "default": mod
  };
};

Object.defineProperty(compile$2, "__esModule", {
  value: !0
}), compile$2.generate = compile$2.compile = void 0;

var boolbase_1$1 = __importDefault$1(boolbase);

compile$2.compile = function(parsed) {
  var a = parsed[0], b = parsed[1] - 1;
  if (b < 0 && a <= 0) return boolbase_1$1.default.falseFunc;
  if (-1 === a) return function(index) {
    return index <= b;
  };
  if (0 === a) return function(index) {
    return index === b;
  };
  if (1 === a) return b < 0 ? boolbase_1$1.default.trueFunc : function(index) {
    return index >= b;
  };
  var absA = Math.abs(a), bMod = (b % absA + absA) % absA;
  return a > 1 ? function(index) {
    return index >= b && index % absA === bMod;
  } : function(index) {
    return index <= b && index % absA === bMod;
  };
}, compile$2.generate = function(parsed) {
  var a = parsed[0], b = parsed[1] - 1, n = 0;
  if (a < 0) {
    var aPos_1 = -a, minValue_1 = (b % aPos_1 + aPos_1) % aPos_1;
    return function() {
      var val = minValue_1 + aPos_1 * n++;
      return val > b ? null : val;
    };
  }
  return 0 === a ? b < 0 ? function() {
    return null;
  } : function() {
    return 0 == n++ ? b : null;
  } : (b < 0 && (b += a * Math.ceil(-b / a)), function() {
    return a * n++ + b;
  });
}, function(exports) {
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.sequence = exports.generate = exports.compile = exports.parse = void 0;
  var parse_js_1 = parse$1w;
  Object.defineProperty(exports, "parse", {
    enumerable: !0,
    get: function() {
      return parse_js_1.parse;
    }
  });
  var compile_js_1 = compile$2;
  Object.defineProperty(exports, "compile", {
    enumerable: !0,
    get: function() {
      return compile_js_1.compile;
    }
  }), Object.defineProperty(exports, "generate", {
    enumerable: !0,
    get: function() {
      return compile_js_1.generate;
    }
  }), exports.default = function(formula) {
    return (0, compile_js_1.compile)((0, parse_js_1.parse)(formula));
  }, exports.sequence = function(formula) {
    return (0, compile_js_1.generate)((0, parse_js_1.parse)(formula));
  };
}(lib), function(exports) {
  var __importDefault = commonjsGlobal && commonjsGlobal.__importDefault || function(mod) {
    return mod && mod.__esModule ? mod : {
      "default": mod
    };
  };
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.filters = void 0;
  var nth_check_1 = __importDefault(lib), boolbase_1 = __importDefault(boolbase);
  function getChildFunc(next, adapter) {
    return function(elem) {
      var parent = adapter.getParent(elem);
      return null != parent && adapter.isTag(parent) && next(elem);
    };
  }
  function dynamicStatePseudo(name) {
    return function(next, _rule, _a) {
      var func = _a.adapter[name];
      return "function" != typeof func ? boolbase_1.default.falseFunc : function(elem) {
        return func(elem) && next(elem);
      };
    };
  }
  exports.filters = {
    contains: function(next, text, _a) {
      var adapter = _a.adapter;
      return function(elem) {
        return next(elem) && adapter.getText(elem).includes(text);
      };
    },
    icontains: function(next, text, _a) {
      var adapter = _a.adapter, itext = text.toLowerCase();
      return function(elem) {
        return next(elem) && adapter.getText(elem).toLowerCase().includes(itext);
      };
    },
    "nth-child": function(next, rule, _a) {
      var adapter = _a.adapter, equals = _a.equals, func = (0, nth_check_1.default)(rule);
      return func === boolbase_1.default.falseFunc ? boolbase_1.default.falseFunc : func === boolbase_1.default.trueFunc ? getChildFunc(next, adapter) : function(elem) {
        for (var siblings = adapter.getSiblings(elem), pos = 0, i = 0; i < siblings.length && !equals(elem, siblings[i]); i++) adapter.isTag(siblings[i]) && pos++;
        return func(pos) && next(elem);
      };
    },
    "nth-last-child": function(next, rule, _a) {
      var adapter = _a.adapter, equals = _a.equals, func = (0, nth_check_1.default)(rule);
      return func === boolbase_1.default.falseFunc ? boolbase_1.default.falseFunc : func === boolbase_1.default.trueFunc ? getChildFunc(next, adapter) : function(elem) {
        for (var siblings = adapter.getSiblings(elem), pos = 0, i = siblings.length - 1; i >= 0 && !equals(elem, siblings[i]); i--) adapter.isTag(siblings[i]) && pos++;
        return func(pos) && next(elem);
      };
    },
    "nth-of-type": function(next, rule, _a) {
      var adapter = _a.adapter, equals = _a.equals, func = (0, nth_check_1.default)(rule);
      return func === boolbase_1.default.falseFunc ? boolbase_1.default.falseFunc : func === boolbase_1.default.trueFunc ? getChildFunc(next, adapter) : function(elem) {
        for (var siblings = adapter.getSiblings(elem), pos = 0, i = 0; i < siblings.length; i++) {
          var currentSibling = siblings[i];
          if (equals(elem, currentSibling)) break;
          adapter.isTag(currentSibling) && adapter.getName(currentSibling) === adapter.getName(elem) && pos++;
        }
        return func(pos) && next(elem);
      };
    },
    "nth-last-of-type": function(next, rule, _a) {
      var adapter = _a.adapter, equals = _a.equals, func = (0, nth_check_1.default)(rule);
      return func === boolbase_1.default.falseFunc ? boolbase_1.default.falseFunc : func === boolbase_1.default.trueFunc ? getChildFunc(next, adapter) : function(elem) {
        for (var siblings = adapter.getSiblings(elem), pos = 0, i = siblings.length - 1; i >= 0; i--) {
          var currentSibling = siblings[i];
          if (equals(elem, currentSibling)) break;
          adapter.isTag(currentSibling) && adapter.getName(currentSibling) === adapter.getName(elem) && pos++;
        }
        return func(pos) && next(elem);
      };
    },
    root: function(next, _rule, _a) {
      var adapter = _a.adapter;
      return function(elem) {
        var parent = adapter.getParent(elem);
        return (null == parent || !adapter.isTag(parent)) && next(elem);
      };
    },
    scope: function(next, rule, options, context) {
      var equals = options.equals;
      return context && 0 !== context.length ? 1 === context.length ? function(elem) {
        return equals(context[0], elem) && next(elem);
      } : function(elem) {
        return context.includes(elem) && next(elem);
      } : exports.filters.root(next, rule, options);
    },
    hover: dynamicStatePseudo("isHovered"),
    visited: dynamicStatePseudo("isVisited"),
    active: dynamicStatePseudo("isActive")
  };
}(filters$1);

var pseudos = {};

Object.defineProperty(pseudos, "__esModule", {
  value: !0
}), pseudos.verifyPseudoArgs = pseudos.pseudos = void 0, pseudos.pseudos = {
  empty: function(elem, _a) {
    var adapter = _a.adapter;
    return !adapter.getChildren(elem).some((function(elem) {
      return adapter.isTag(elem) || "" !== adapter.getText(elem);
    }));
  },
  "first-child": function(elem, _a) {
    var adapter = _a.adapter, equals = _a.equals;
    if (adapter.prevElementSibling) return null == adapter.prevElementSibling(elem);
    var firstChild = adapter.getSiblings(elem).find((function(elem) {
      return adapter.isTag(elem);
    }));
    return null != firstChild && equals(elem, firstChild);
  },
  "last-child": function(elem, _a) {
    for (var adapter = _a.adapter, equals = _a.equals, siblings = adapter.getSiblings(elem), i = siblings.length - 1; i >= 0; i--) {
      if (equals(elem, siblings[i])) return !0;
      if (adapter.isTag(siblings[i])) break;
    }
    return !1;
  },
  "first-of-type": function(elem, _a) {
    for (var adapter = _a.adapter, equals = _a.equals, siblings = adapter.getSiblings(elem), elemName = adapter.getName(elem), i = 0; i < siblings.length; i++) {
      var currentSibling = siblings[i];
      if (equals(elem, currentSibling)) return !0;
      if (adapter.isTag(currentSibling) && adapter.getName(currentSibling) === elemName) break;
    }
    return !1;
  },
  "last-of-type": function(elem, _a) {
    for (var adapter = _a.adapter, equals = _a.equals, siblings = adapter.getSiblings(elem), elemName = adapter.getName(elem), i = siblings.length - 1; i >= 0; i--) {
      var currentSibling = siblings[i];
      if (equals(elem, currentSibling)) return !0;
      if (adapter.isTag(currentSibling) && adapter.getName(currentSibling) === elemName) break;
    }
    return !1;
  },
  "only-of-type": function(elem, _a) {
    var adapter = _a.adapter, equals = _a.equals, elemName = adapter.getName(elem);
    return adapter.getSiblings(elem).every((function(sibling) {
      return equals(elem, sibling) || !adapter.isTag(sibling) || adapter.getName(sibling) !== elemName;
    }));
  },
  "only-child": function(elem, _a) {
    var adapter = _a.adapter, equals = _a.equals;
    return adapter.getSiblings(elem).every((function(sibling) {
      return equals(elem, sibling) || !adapter.isTag(sibling);
    }));
  }
}, pseudos.verifyPseudoArgs = function(func, name, subselect, argIndex) {
  if (null === subselect) {
    if (func.length > argIndex) throw new Error("Pseudo-class :".concat(name, " requires an argument"));
  } else if (func.length === argIndex) throw new Error("Pseudo-class :".concat(name, " doesn't have any arguments"));
};

var aliases = {};

Object.defineProperty(aliases, "__esModule", {
  value: !0
}), aliases.aliases = void 0, aliases.aliases = {
  "any-link": ":is(a, area, link)[href]",
  link: ":any-link:not(:visited)",
  disabled: ":is(\n        :is(button, input, select, textarea, optgroup, option)[disabled],\n        optgroup[disabled] > option,\n        fieldset[disabled]:not(fieldset[disabled] legend:first-of-type *)\n    )",
  enabled: ":not(:disabled)",
  checked: ":is(:is(input[type=radio], input[type=checkbox])[checked], option:selected)",
  required: ":is(input, select, textarea)[required]",
  optional: ":is(input, select, textarea):not([required])",
  selected: "option:is([selected], select:not([multiple]):not(:has(> option[selected])) > :first-of-type)",
  checkbox: "[type=checkbox]",
  file: "[type=file]",
  password: "[type=password]",
  radio: "[type=radio]",
  reset: "[type=reset]",
  image: "[type=image]",
  submit: "[type=submit]",
  parent: ":not(:empty)",
  header: ":is(h1, h2, h3, h4, h5, h6)",
  button: ":is(button, input[type=button])",
  input: ":is(input, textarea, select, button)",
  text: "input:is(:not([type!='']), [type=text])"
};

var subselects = {};

!function(exports) {
  var __spreadArray = commonjsGlobal && commonjsGlobal.__spreadArray || function(to, from, pack) {
    if (pack || 2 === arguments.length) for (var ar, i = 0, l = from.length; i < l; i++) !ar && i in from || (ar || (ar = Array.prototype.slice.call(from, 0, i)), 
    ar[i] = from[i]);
    return to.concat(ar || Array.prototype.slice.call(from));
  }, __importDefault = commonjsGlobal && commonjsGlobal.__importDefault || function(mod) {
    return mod && mod.__esModule ? mod : {
      "default": mod
    };
  };
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.subselects = exports.getNextSiblings = exports.ensureIsTag = exports.PLACEHOLDER_ELEMENT = void 0;
  var boolbase_1 = __importDefault(boolbase), sort_js_1 = sort;
  function ensureIsTag(next, adapter) {
    return next === boolbase_1.default.falseFunc ? boolbase_1.default.falseFunc : function(elem) {
      return adapter.isTag(elem) && next(elem);
    };
  }
  function getNextSiblings(elem, adapter) {
    var siblings = adapter.getSiblings(elem);
    if (siblings.length <= 1) return [];
    var elemIndex = siblings.indexOf(elem);
    return elemIndex < 0 || elemIndex === siblings.length - 1 ? [] : siblings.slice(elemIndex + 1).filter(adapter.isTag);
  }
  function copyOptions(options) {
    return {
      xmlMode: !!options.xmlMode,
      lowerCaseAttributeNames: !!options.lowerCaseAttributeNames,
      lowerCaseTags: !!options.lowerCaseTags,
      quirksMode: !!options.quirksMode,
      cacheResults: !!options.cacheResults,
      pseudos: options.pseudos,
      adapter: options.adapter,
      equals: options.equals
    };
  }
  exports.PLACEHOLDER_ELEMENT = {}, exports.ensureIsTag = ensureIsTag, exports.getNextSiblings = getNextSiblings;
  var is = function(next, token, options, context, compileToken) {
    var func = compileToken(token, copyOptions(options), context);
    return func === boolbase_1.default.trueFunc ? next : func === boolbase_1.default.falseFunc ? boolbase_1.default.falseFunc : function(elem) {
      return func(elem) && next(elem);
    };
  };
  exports.subselects = {
    is,
    matches: is,
    where: is,
    not: function(next, token, options, context, compileToken) {
      var func = compileToken(token, copyOptions(options), context);
      return func === boolbase_1.default.falseFunc ? next : func === boolbase_1.default.trueFunc ? boolbase_1.default.falseFunc : function(elem) {
        return !func(elem) && next(elem);
      };
    },
    has: function(next, subselect, options, _context, compileToken) {
      var adapter = options.adapter, opts = copyOptions(options);
      opts.relativeSelector = !0;
      var context = subselect.some((function(s) {
        return s.some(sort_js_1.isTraversal);
      })) ? [ exports.PLACEHOLDER_ELEMENT ] : void 0, compiled = compileToken(subselect, opts, context);
      if (compiled === boolbase_1.default.falseFunc) return boolbase_1.default.falseFunc;
      var hasElement = ensureIsTag(compiled, adapter);
      if (context && compiled !== boolbase_1.default.trueFunc) {
        var _a = compiled.shouldTestNextSiblings, shouldTestNextSiblings_1 = void 0 !== _a && _a;
        return function(elem) {
          if (!next(elem)) return !1;
          context[0] = elem;
          var childs = adapter.getChildren(elem), nextElements = shouldTestNextSiblings_1 ? __spreadArray(__spreadArray([], childs, !0), getNextSiblings(elem, adapter), !0) : childs;
          return adapter.existsOne(hasElement, nextElements);
        };
      }
      return function(elem) {
        return next(elem) && adapter.existsOne(hasElement, adapter.getChildren(elem));
      };
    }
  };
}(subselects), function(exports) {
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.compilePseudoSelector = exports.aliases = exports.pseudos = exports.filters = void 0;
  var css_what_1 = require$$0, filters_js_1 = filters$1;
  Object.defineProperty(exports, "filters", {
    enumerable: !0,
    get: function() {
      return filters_js_1.filters;
    }
  });
  var pseudos_js_1 = pseudos;
  Object.defineProperty(exports, "pseudos", {
    enumerable: !0,
    get: function() {
      return pseudos_js_1.pseudos;
    }
  });
  var aliases_js_1 = aliases;
  Object.defineProperty(exports, "aliases", {
    enumerable: !0,
    get: function() {
      return aliases_js_1.aliases;
    }
  });
  var subselects_js_1 = subselects;
  exports.compilePseudoSelector = function(next, selector, options, context, compileToken) {
    var _a, name = selector.name, data = selector.data;
    if (Array.isArray(data)) {
      if (!(name in subselects_js_1.subselects)) throw new Error("Unknown pseudo-class :".concat(name, "(").concat(data, ")"));
      return subselects_js_1.subselects[name](next, data, options, context, compileToken);
    }
    var userPseudo = null === (_a = options.pseudos) || void 0 === _a ? void 0 : _a[name], stringPseudo = "string" == typeof userPseudo ? userPseudo : aliases_js_1.aliases[name];
    if ("string" == typeof stringPseudo) {
      if (null != data) throw new Error("Pseudo ".concat(name, " doesn't have any arguments"));
      var alias = (0, css_what_1.parse)(stringPseudo);
      return subselects_js_1.subselects.is(next, alias, options, context, compileToken);
    }
    if ("function" == typeof userPseudo) return (0, pseudos_js_1.verifyPseudoArgs)(userPseudo, name, data, 1), 
    function(elem) {
      return userPseudo(elem, data) && next(elem);
    };
    if (name in filters_js_1.filters) return filters_js_1.filters[name](next, data, options, context);
    if (name in pseudos_js_1.pseudos) {
      var pseudo_1 = pseudos_js_1.pseudos[name];
      return (0, pseudos_js_1.verifyPseudoArgs)(pseudo_1, name, data, 2), function(elem) {
        return pseudo_1(elem, options, data) && next(elem);
      };
    }
    throw new Error("Unknown pseudo-class :".concat(name));
  };
}(pseudoSelectors), Object.defineProperty(general, "__esModule", {
  value: !0
}), general.compileGeneralSelector = void 0;

var attributes_js_1 = attributes, index_js_1 = pseudoSelectors, css_what_1$1 = require$$0;

function getElementParent(node, adapter) {
  var parent = adapter.getParent(node);
  return parent && adapter.isTag(parent) ? parent : null;
}

general.compileGeneralSelector = function(next, selector, options, context, compileToken) {
  var adapter = options.adapter, equals = options.equals;
  switch (selector.type) {
   case css_what_1$1.SelectorType.PseudoElement:
    throw new Error("Pseudo-elements are not supported by css-select");

   case css_what_1$1.SelectorType.ColumnCombinator:
    throw new Error("Column combinators are not yet supported by css-select");

   case css_what_1$1.SelectorType.Attribute:
    if (null != selector.namespace) throw new Error("Namespaced attributes are not yet supported by css-select");
    return options.xmlMode && !options.lowerCaseAttributeNames || (selector.name = selector.name.toLowerCase()), 
    attributes_js_1.attributeRules[selector.action](next, selector, options);

   case css_what_1$1.SelectorType.Pseudo:
    return (0, index_js_1.compilePseudoSelector)(next, selector, options, context, compileToken);

   case css_what_1$1.SelectorType.Tag:
    if (null != selector.namespace) throw new Error("Namespaced tag names are not yet supported by css-select");
    var name_1 = selector.name;
    return options.xmlMode && !options.lowerCaseTags || (name_1 = name_1.toLowerCase()), 
    function(elem) {
      return adapter.getName(elem) === name_1 && next(elem);
    };

   case css_what_1$1.SelectorType.Descendant:
    if (!1 === options.cacheResults || "undefined" == typeof WeakSet) return function(elem) {
      for (var current = elem; current = getElementParent(current, adapter); ) if (next(current)) return !0;
      return !1;
    };
    var isFalseCache_1 = new WeakSet;
    return function(elem) {
      for (var current = elem; current = getElementParent(current, adapter); ) if (!isFalseCache_1.has(current)) {
        if (adapter.isTag(current) && next(current)) return !0;
        isFalseCache_1.add(current);
      }
      return !1;
    };

   case "_flexibleDescendant":
    return function(elem) {
      var current = elem;
      do {
        if (next(current)) return !0;
      } while (current = getElementParent(current, adapter));
      return !1;
    };

   case css_what_1$1.SelectorType.Parent:
    return function(elem) {
      return adapter.getChildren(elem).some((function(elem) {
        return adapter.isTag(elem) && next(elem);
      }));
    };

   case css_what_1$1.SelectorType.Child:
    return function(elem) {
      var parent = adapter.getParent(elem);
      return null != parent && adapter.isTag(parent) && next(parent);
    };

   case css_what_1$1.SelectorType.Sibling:
    return function(elem) {
      for (var siblings = adapter.getSiblings(elem), i = 0; i < siblings.length; i++) {
        var currentSibling = siblings[i];
        if (equals(elem, currentSibling)) break;
        if (adapter.isTag(currentSibling) && next(currentSibling)) return !0;
      }
      return !1;
    };

   case css_what_1$1.SelectorType.Adjacent:
    return adapter.prevElementSibling ? function(elem) {
      var previous = adapter.prevElementSibling(elem);
      return null != previous && next(previous);
    } : function(elem) {
      for (var lastElement, siblings = adapter.getSiblings(elem), i = 0; i < siblings.length; i++) {
        var currentSibling = siblings[i];
        if (equals(elem, currentSibling)) break;
        adapter.isTag(currentSibling) && (lastElement = currentSibling);
      }
      return !!lastElement && next(lastElement);
    };

   case css_what_1$1.SelectorType.Universal:
    if (null != selector.namespace && "*" !== selector.namespace) throw new Error("Namespaced universal selectors are not yet supported by css-select");
    return next;
  }
};

var __createBinding = commonjsGlobal && commonjsGlobal.__createBinding || (Object.create ? function(o, m, k, k2) {
  void 0 === k2 && (k2 = k);
  var desc = Object.getOwnPropertyDescriptor(m, k);
  desc && !("get" in desc ? !m.__esModule : desc.writable || desc.configurable) || (desc = {
    enumerable: !0,
    get: function() {
      return m[k];
    }
  }), Object.defineProperty(o, k2, desc);
} : function(o, m, k, k2) {
  void 0 === k2 && (k2 = k), o[k2] = m[k];
}), __setModuleDefault = commonjsGlobal && commonjsGlobal.__setModuleDefault || (Object.create ? function(o, v) {
  Object.defineProperty(o, "default", {
    enumerable: !0,
    value: v
  });
} : function(o, v) {
  o.default = v;
}), __importStar = commonjsGlobal && commonjsGlobal.__importStar || function(mod) {
  if (mod && mod.__esModule) return mod;
  var result = {};
  if (null != mod) for (var k in mod) "default" !== k && Object.prototype.hasOwnProperty.call(mod, k) && __createBinding(result, mod, k);
  return __setModuleDefault(result, mod), result;
}, __importDefault = commonjsGlobal && commonjsGlobal.__importDefault || function(mod) {
  return mod && mod.__esModule ? mod : {
    "default": mod
  };
};

Object.defineProperty(compile$3, "__esModule", {
  value: !0
}), compile$3.compileToken = compile$3.compileUnsafe = compile$3.compile = void 0;

var css_what_1 = require$$0, boolbase_1 = __importDefault(boolbase), sort_js_1 = __importStar(sort), general_js_1 = general, subselects_js_1 = subselects;

function compileUnsafe(selector, options, context) {
  return compileToken("string" == typeof selector ? (0, css_what_1.parse)(selector) : selector, options, context);
}

function includesScopePseudo(t) {
  return t.type === css_what_1.SelectorType.Pseudo && ("scope" === t.name || Array.isArray(t.data) && t.data.some((function(data) {
    return data.some(includesScopePseudo);
  })));
}

compile$3.compile = function(selector, options, context) {
  var next = compileUnsafe(selector, options, context);
  return (0, subselects_js_1.ensureIsTag)(next, options.adapter);
}, compile$3.compileUnsafe = compileUnsafe;

var DESCENDANT_TOKEN = {
  type: css_what_1.SelectorType.Descendant
}, FLEXIBLE_DESCENDANT_TOKEN = {
  type: "_flexibleDescendant"
}, SCOPE_TOKEN = {
  type: css_what_1.SelectorType.Pseudo,
  name: "scope",
  data: null
};

function compileToken(token, options, context) {
  var _a;
  token.forEach(sort_js_1.default), context = null !== (_a = options.context) && void 0 !== _a ? _a : context;
  var isArrayContext = Array.isArray(context), finalContext = context && (Array.isArray(context) ? context : [ context ]);
  if (!1 !== options.relativeSelector) !function(token, _a, context) {
    for (var adapter = _a.adapter, hasContext = !!(null == context ? void 0 : context.every((function(e) {
      var parent = adapter.isTag(e) && adapter.getParent(e);
      return e === subselects_js_1.PLACEHOLDER_ELEMENT || parent && adapter.isTag(parent);
    }))), _i = 0, token_1 = token; _i < token_1.length; _i++) {
      var t = token_1[_i];
      if (t.length > 0 && (0, sort_js_1.isTraversal)(t[0]) && t[0].type !== css_what_1.SelectorType.Descendant) ; else {
        if (!hasContext || t.some(includesScopePseudo)) continue;
        t.unshift(DESCENDANT_TOKEN);
      }
      t.unshift(SCOPE_TOKEN);
    }
  }(token, options, finalContext); else if (token.some((function(t) {
    return t.length > 0 && (0, sort_js_1.isTraversal)(t[0]);
  }))) throw new Error("Relative selectors are not allowed when the `relativeSelector` option is disabled");
  var shouldTestNextSiblings = !1, query = token.map((function(rules) {
    if (rules.length >= 2) {
      var first = rules[0], second = rules[1];
      first.type !== css_what_1.SelectorType.Pseudo || "scope" !== first.name || (isArrayContext && second.type === css_what_1.SelectorType.Descendant ? rules[1] = FLEXIBLE_DESCENDANT_TOKEN : second.type !== css_what_1.SelectorType.Adjacent && second.type !== css_what_1.SelectorType.Sibling || (shouldTestNextSiblings = !0));
    }
    return function(rules, options, context) {
      var _a;
      return rules.reduce((function(previous, rule) {
        return previous === boolbase_1.default.falseFunc ? boolbase_1.default.falseFunc : (0, 
        general_js_1.compileGeneralSelector)(previous, rule, options, context, compileToken);
      }), null !== (_a = options.rootFunc) && void 0 !== _a ? _a : boolbase_1.default.trueFunc);
    }(rules, options, finalContext);
  })).reduce(reduceRules, boolbase_1.default.falseFunc);
  return query.shouldTestNextSiblings = shouldTestNextSiblings, query;
}

function reduceRules(a, b) {
  return b === boolbase_1.default.falseFunc || a === boolbase_1.default.trueFunc ? a : a === boolbase_1.default.falseFunc || b === boolbase_1.default.trueFunc ? b : function(elem) {
    return a(elem) || b(elem);
  };
}

compile$3.compileToken = compileToken, function(exports) {
  var __createBinding = commonjsGlobal && commonjsGlobal.__createBinding || (Object.create ? function(o, m, k, k2) {
    void 0 === k2 && (k2 = k);
    var desc = Object.getOwnPropertyDescriptor(m, k);
    desc && !("get" in desc ? !m.__esModule : desc.writable || desc.configurable) || (desc = {
      enumerable: !0,
      get: function() {
        return m[k];
      }
    }), Object.defineProperty(o, k2, desc);
  } : function(o, m, k, k2) {
    void 0 === k2 && (k2 = k), o[k2] = m[k];
  }), __setModuleDefault = commonjsGlobal && commonjsGlobal.__setModuleDefault || (Object.create ? function(o, v) {
    Object.defineProperty(o, "default", {
      enumerable: !0,
      value: v
    });
  } : function(o, v) {
    o.default = v;
  }), __importStar = commonjsGlobal && commonjsGlobal.__importStar || function(mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (null != mod) for (var k in mod) "default" !== k && Object.prototype.hasOwnProperty.call(mod, k) && __createBinding(result, mod, k);
    return __setModuleDefault(result, mod), result;
  }, __importDefault = commonjsGlobal && commonjsGlobal.__importDefault || function(mod) {
    return mod && mod.__esModule ? mod : {
      "default": mod
    };
  };
  Object.defineProperty(exports, "__esModule", {
    value: !0
  }), exports.aliases = exports.pseudos = exports.filters = exports.is = exports.selectOne = exports.selectAll = exports.prepareContext = exports._compileToken = exports._compileUnsafe = exports.compile = void 0;
  var DomUtils = __importStar(lib$5), boolbase_1 = __importDefault(boolbase), compile_js_1 = compile$3, subselects_js_1 = subselects, defaultEquals = function(a, b) {
    return a === b;
  }, defaultOptions = {
    adapter: DomUtils,
    equals: defaultEquals
  };
  function convertOptionFormats(options) {
    var _a, _b, _c, _d, opts = null != options ? options : defaultOptions;
    return null !== (_a = opts.adapter) && void 0 !== _a || (opts.adapter = DomUtils), 
    null !== (_b = opts.equals) && void 0 !== _b || (opts.equals = null !== (_d = null === (_c = opts.adapter) || void 0 === _c ? void 0 : _c.equals) && void 0 !== _d ? _d : defaultEquals), 
    opts;
  }
  function wrapCompile(func) {
    return function(selector, options, context) {
      var opts = convertOptionFormats(options);
      return func(selector, opts, context);
    };
  }
  function getSelectorFunc(searchFunc) {
    return function(query, elements, options) {
      var opts = convertOptionFormats(options);
      "function" != typeof query && (query = (0, compile_js_1.compileUnsafe)(query, opts, elements));
      var filteredElements = prepareContext(elements, opts.adapter, query.shouldTestNextSiblings);
      return searchFunc(query, filteredElements, opts);
    };
  }
  function prepareContext(elems, adapter, shouldTestNextSiblings) {
    return void 0 === shouldTestNextSiblings && (shouldTestNextSiblings = !1), shouldTestNextSiblings && (elems = function(elem, adapter) {
      for (var elems = Array.isArray(elem) ? elem.slice(0) : [ elem ], elemsLength = elems.length, i = 0; i < elemsLength; i++) {
        var nextSiblings = (0, subselects_js_1.getNextSiblings)(elems[i], adapter);
        elems.push.apply(elems, nextSiblings);
      }
      return elems;
    }(elems, adapter)), Array.isArray(elems) ? adapter.removeSubsets(elems) : adapter.getChildren(elems);
  }
  exports.compile = wrapCompile(compile_js_1.compile), exports._compileUnsafe = wrapCompile(compile_js_1.compileUnsafe), 
  exports._compileToken = wrapCompile(compile_js_1.compileToken), exports.prepareContext = prepareContext, 
  exports.selectAll = getSelectorFunc((function(query, elems, options) {
    return query !== boolbase_1.default.falseFunc && elems && 0 !== elems.length ? options.adapter.findAll(query, elems) : [];
  })), exports.selectOne = getSelectorFunc((function(query, elems, options) {
    return query !== boolbase_1.default.falseFunc && elems && 0 !== elems.length ? options.adapter.findOne(query, elems) : null;
  })), exports.is = function(elem, query, options) {
    var opts = convertOptionFormats(options);
    return ("function" == typeof query ? query : (0, compile_js_1.compile)(query, opts))(elem);
  }, exports.default = exports.selectAll;
  var index_js_1 = pseudoSelectors;
  Object.defineProperty(exports, "filters", {
    enumerable: !0,
    get: function() {
      return index_js_1.filters;
    }
  }), Object.defineProperty(exports, "pseudos", {
    enumerable: !0,
    get: function() {
      return index_js_1.pseudos;
    }
  }), Object.defineProperty(exports, "aliases", {
    enumerable: !0,
    get: function() {
      return index_js_1.aliases;
    }
  });
}(lib$6);

const isTag = node => "element" === node.type, existsOne = (test, elems) => elems.some((elem => !!isTag(elem) && (test(elem) || existsOne(test, getChildren(elem))))), getChildren = node => node.children || [], getParent = node => node.parentNode || null, findAll$3 = (test, elems) => {
  const result = [];
  for (const elem of elems) isTag(elem) && (test(elem) && result.push(elem), result.push(...findAll$3(test, getChildren(elem))));
  return result;
}, findOne = (test, elems) => {
  for (const elem of elems) if (isTag(elem)) {
    if (test(elem)) return elem;
    const result = findOne(test, getChildren(elem));
    if (result) return result;
  }
  return null;
}, svgoCssSelectAdapter = {
  isTag,
  existsOne,
  getAttributeValue: (elem, name) => elem.attributes[name],
  getChildren,
  getName: elemAst => elemAst.name,
  getParent,
  getSiblings: elem => {
    var parent = getParent(elem);
    return parent ? getChildren(parent) : [];
  },
  getText: node => "text" === node.children[0].type && "cdata" === node.children[0].type ? node.children[0].value : "",
  hasAttrib: (elem, name) => void 0 !== elem.attributes[name],
  removeSubsets: nodes => {
    let node, ancestor, replace, idx = nodes.length;
    for (;--idx > -1; ) {
      for (node = ancestor = nodes[idx], nodes[idx] = null, replace = !0; ancestor; ) {
        if (nodes.includes(ancestor)) {
          replace = !1, nodes.splice(idx, 1);
          break;
        }
        ancestor = getParent(ancestor);
      }
      replace && (nodes[idx] = node);
    }
    return nodes;
  },
  findAll: findAll$3,
  findOne
};

var cssSelectAdapter = svgoCssSelectAdapter;

const {selectAll, selectOne, is} = lib$6, cssSelectOptions = {
  xmlMode: !0,
  adapter: cssSelectAdapter
};

xast.querySelectorAll = (node, selector) => selectAll(selector, node, cssSelectOptions);

xast.querySelector = (node, selector) => selectOne(selector, node, cssSelectOptions);

xast.matches = (node, selector) => is(node, selector, cssSelectOptions);

const visitSkip$7 = Symbol();

xast.visitSkip = visitSkip$7;

const visit$7 = (node, visitor, parentNode) => {
  const callbacks = visitor[node.type];
  if (callbacks && callbacks.enter) {
    if (callbacks.enter(node, parentNode) === visitSkip$7) return;
  }
  if ("root" === node.type) for (const child of node.children) visit$7(child, visitor, node);
  if ("element" === node.type && parentNode.children.includes(node)) for (const child of node.children) visit$7(child, visitor, node);
  callbacks && callbacks.exit && callbacks.exit(node, parentNode);
};

xast.visit = visit$7;

xast.detachNodeFromParent = (node, parentNode) => {
  parentNode.children = parentNode.children.filter((child => child !== node));
};

const {visit: visit$6} = xast;

tools.encodeSVGDatauri = (str, type) => {
  var prefix = "data:image/svg+xml";
  return type && "base64" !== type ? "enc" === type ? str = prefix + "," + encodeURIComponent(str) : "unenc" === type && (str = prefix + "," + str) : str = (prefix += ";base64,") + Buffer.from(str).toString("base64"), 
  str;
}, tools.decodeSVGDatauri = str => {
  var match = /data:image\/svg\+xml(;charset=[^;,]*)?(;base64)?,(.*)/.exec(str);
  if (!match) return str;
  var data = match[3];
  return match[2] ? str = Buffer.from(data, "base64").toString("utf8") : "%" === data.charAt(0) ? str = decodeURIComponent(data) : "<" === data.charAt(0) && (str = data), 
  str;
}, tools.cleanupOutData = (data, params, command) => {
  let delimiter, prev, str = "";
  return data.forEach(((item, i) => {
    if (delimiter = " ", 0 == i && (delimiter = ""), params.noSpaceAfterFlags && ("A" == command || "a" == command)) {
      var pos = i % 7;
      4 != pos && 5 != pos || (delimiter = "");
    }
    const itemStr = params.leadingZero ? removeLeadingZero$4(item) : item.toString();
    params.negativeExtraSpace && "" != delimiter && (item < 0 || "." === itemStr.charAt(0) && prev % 1 != 0) && (delimiter = ""), 
    prev = item, str += delimiter + itemStr;
  })), str;
};

const removeLeadingZero$4 = num => {
  var strNum = num.toString();
  return 0 < num && num < 1 && "0" === strNum.charAt(0) ? strNum = strNum.slice(1) : -1 < num && num < 0 && "0" === strNum.charAt(1) && (strNum = strNum.charAt(0) + strNum.slice(2)), 
  strNum;
};

function invokePlugins$1(ast, info, plugins, overrides, globalOverrides) {
  for (const plugin of plugins) {
    const override = null == overrides ? null : overrides[plugin.name];
    if (!1 === override) continue;
    const params = {
      ...plugin.params,
      ...globalOverrides,
      ...override
    }, visitor = plugin.fn(ast, params, info);
    null != visitor && visit$6(ast, visitor);
  }
}

tools.removeLeadingZero = removeLeadingZero$4, tools.invokePlugins = invokePlugins$1, 
tools.createPreset = function({name, plugins}) {
  return {
    name,
    fn: (ast, params, info) => {
      const {floatPrecision, overrides} = params, globalOverrides = {};
      null != floatPrecision && (globalOverrides.floatPrecision = floatPrecision), invokePlugins$1(ast, info, plugins, overrides, globalOverrides);
    }
  };
};

var removeDoctype$2 = {};

const {detachNodeFromParent: detachNodeFromParent$l} = xast;

removeDoctype$2.name = "removeDoctype", removeDoctype$2.description = "removes doctype declaration", 
removeDoctype$2.fn = () => ({
  doctype: {
    enter: (node, parentNode) => {
      detachNodeFromParent$l(node, parentNode);
    }
  }
});

var removeXMLProcInst$2 = {};

const {detachNodeFromParent: detachNodeFromParent$k} = xast;

removeXMLProcInst$2.name = "removeXMLProcInst", removeXMLProcInst$2.description = "removes XML processing instructions", 
removeXMLProcInst$2.fn = () => ({
  instruction: {
    enter: (node, parentNode) => {
      "xml" === node.name && detachNodeFromParent$k(node, parentNode);
    }
  }
});

var removeComments$2 = {};

const {detachNodeFromParent: detachNodeFromParent$j} = xast;

removeComments$2.name = "removeComments", removeComments$2.description = "removes comments", 
removeComments$2.fn = () => ({
  comment: {
    enter: (node, parentNode) => {
      "!" !== node.value.charAt(0) && detachNodeFromParent$j(node, parentNode);
    }
  }
});

var removeMetadata$2 = {};

const {detachNodeFromParent: detachNodeFromParent$i} = xast;

removeMetadata$2.name = "removeMetadata", removeMetadata$2.description = "removes <metadata>", 
removeMetadata$2.fn = () => ({
  element: {
    enter: (node, parentNode) => {
      "metadata" === node.name && detachNodeFromParent$i(node, parentNode);
    }
  }
});

var removeEditorsNSData$2 = {};

const {detachNodeFromParent: detachNodeFromParent$h} = xast, {editorNamespaces} = _collections;

removeEditorsNSData$2.name = "removeEditorsNSData", removeEditorsNSData$2.description = "removes editors namespaces, elements and attributes", 
removeEditorsNSData$2.fn = (_root, params) => {
  let namespaces = editorNamespaces;
  Array.isArray(params.additionalNamespaces) && (namespaces = [ ...editorNamespaces, ...params.additionalNamespaces ]);
  const prefixes = [];
  return {
    element: {
      enter: (node, parentNode) => {
        if ("svg" === node.name) for (const [name, value] of Object.entries(node.attributes)) name.startsWith("xmlns:") && namespaces.includes(value) && (prefixes.push(name.slice(6)), 
        delete node.attributes[name]);
        for (const name of Object.keys(node.attributes)) if (name.includes(":")) {
          const [prefix] = name.split(":");
          prefixes.includes(prefix) && delete node.attributes[name];
        }
        if (node.name.includes(":")) {
          const [prefix] = node.name.split(":");
          prefixes.includes(prefix) && detachNodeFromParent$h(node, parentNode);
        }
      }
    }
  };
};

var cleanupAttrs$2 = {
  name: "cleanupAttrs",
  description: "cleanups attributes from newlines, trailing and repeating spaces"
};

const regNewlinesNeedSpace = /(\S)\r?\n(\S)/g, regNewlines = /\r?\n/g, regSpaces = /\s{2,}/g;

cleanupAttrs$2.fn = (root, params) => {
  const {newlines = !0, trim = !0, spaces = !0} = params;
  return {
    element: {
      enter: node => {
        for (const name of Object.keys(node.attributes)) newlines && (node.attributes[name] = node.attributes[name].replace(regNewlinesNeedSpace, ((match, p1, p2) => p1 + " " + p2)), 
        node.attributes[name] = node.attributes[name].replace(regNewlines, "")), trim && (node.attributes[name] = node.attributes[name].trim()), 
        spaces && (node.attributes[name] = node.attributes[name].replace(regSpaces, " "));
      }
    }
  };
};

var mergeStyles$2 = {};

const {visitSkip: visitSkip$6, detachNodeFromParent: detachNodeFromParent$g} = xast;

mergeStyles$2.name = "mergeStyles", mergeStyles$2.description = "merge multiple style elements into one", 
mergeStyles$2.fn = () => {
  let firstStyleElement = null, collectedStyles = "", styleContentType = "text";
  return {
    element: {
      enter: (node, parentNode) => {
        if ("foreignObject" === node.name) return visitSkip$6;
        if ("style" !== node.name) return;
        if (null != node.attributes.type && "" !== node.attributes.type && "text/css" !== node.attributes.type) return;
        let css = "";
        for (const child of node.children) "text" === child.type && (css += child.value), 
        "cdata" === child.type && (styleContentType = "cdata", css += child.value);
        if (0 !== css.trim().length) if (null == node.attributes.media ? collectedStyles += css : (collectedStyles += `@media ${node.attributes.media}{${css}}`, 
        delete node.attributes.media), null == firstStyleElement) firstStyleElement = node; else {
          detachNodeFromParent$g(node, parentNode);
          const child = {
            type: styleContentType,
            value: collectedStyles
          };
          Object.defineProperty(child, "parentNode", {
            writable: !0,
            value: firstStyleElement
          }), firstStyleElement.children = [ child ];
        } else detachNodeFromParent$g(node, parentNode);
      }
    }
  };
};

var inlineStyles$2 = {}, cjs$2 = {}, tokenizer$5 = {}, types$1I = {};

types$1I.AtKeyword = 3, types$1I.BadString = 6, types$1I.BadUrl = 8, types$1I.CDC = 15, 
types$1I.CDO = 14, types$1I.Colon = 16, types$1I.Comma = 18, types$1I.Comment = 25, 
types$1I.Delim = 9, types$1I.Dimension = 12, types$1I.EOF = 0, types$1I.Function = 2, 
types$1I.Hash = 4, types$1I.Ident = 1, types$1I.LeftCurlyBracket = 23, types$1I.LeftParenthesis = 21, 
types$1I.LeftSquareBracket = 19, types$1I.Number = 10, types$1I.Percentage = 11, 
types$1I.RightCurlyBracket = 24, types$1I.RightParenthesis = 22, types$1I.RightSquareBracket = 20, 
types$1I.Semicolon = 17, types$1I.String = 5, types$1I.Url = 7, types$1I.WhiteSpace = 13;

var charCodeDefinitions$p = {};

const EOF$2 = 0;

function isDigit$2(code) {
  return code >= 0x0030 && code <= 0x0039;
}

function isUppercaseLetter$1(code) {
  return code >= 0x0041 && code <= 0x005A;
}

function isLowercaseLetter$1(code) {
  return code >= 0x0061 && code <= 0x007A;
}

function isLetter$1(code) {
  return isUppercaseLetter$1(code) || isLowercaseLetter$1(code);
}

function isNonAscii$1(code) {
  return code >= 0x0080;
}

function isNameStart$1(code) {
  return isLetter$1(code) || isNonAscii$1(code) || 0x005F === code;
}

function isNonPrintable$1(code) {
  return code >= 0x0000 && code <= 0x0008 || 0x000B === code || code >= 0x000E && code <= 0x001F || 0x007F === code;
}

function isNewline$1(code) {
  return 0x000A === code || 0x000D === code || 0x000C === code;
}

function isWhiteSpace$1(code) {
  return isNewline$1(code) || 0x0020 === code || 0x0009 === code;
}

function isValidEscape$1(first, second) {
  return 0x005C === first && (!isNewline$1(second) && second !== EOF$2);
}

const CATEGORY$1 = new Array(0x80);

for (let i = 0; i < CATEGORY$1.length; i++) CATEGORY$1[i] = (isWhiteSpace$1(i) ? 130 : isDigit$2(i) && 131) || isNameStart$1(i) && 132 || isNonPrintable$1(i) && 133 || i || 128;

charCodeDefinitions$p.DigitCategory = 131, charCodeDefinitions$p.EofCategory = 128, 
charCodeDefinitions$p.NameStartCategory = 132, charCodeDefinitions$p.NonPrintableCategory = 133, 
charCodeDefinitions$p.WhiteSpaceCategory = 130, charCodeDefinitions$p.charCodeCategory = function(code) {
  return code < 0x80 ? CATEGORY$1[code] : 132;
}, charCodeDefinitions$p.isBOM = function(code) {
  return 0xFEFF === code || 0xFFFE === code ? 1 : 0;
}, charCodeDefinitions$p.isDigit = isDigit$2, charCodeDefinitions$p.isHexDigit = function(code) {
  return isDigit$2(code) || code >= 0x0041 && code <= 0x0046 || code >= 0x0061 && code <= 0x0066;
}, charCodeDefinitions$p.isIdentifierStart = function(first, second, third) {
  return 0x002D === first ? isNameStart$1(second) || 0x002D === second || isValidEscape$1(second, third) : !!isNameStart$1(first) || 0x005C === first && isValidEscape$1(first, second);
}, charCodeDefinitions$p.isLetter = isLetter$1, charCodeDefinitions$p.isLowercaseLetter = isLowercaseLetter$1, 
charCodeDefinitions$p.isName = function(code) {
  return isNameStart$1(code) || isDigit$2(code) || 0x002D === code;
}, charCodeDefinitions$p.isNameStart = isNameStart$1, charCodeDefinitions$p.isNewline = isNewline$1, 
charCodeDefinitions$p.isNonAscii = isNonAscii$1, charCodeDefinitions$p.isNonPrintable = isNonPrintable$1, 
charCodeDefinitions$p.isNumberStart = function(first, second, third) {
  return 0x002B === first || 0x002D === first ? isDigit$2(second) ? 2 : 0x002E === second && isDigit$2(third) ? 3 : 0 : 0x002E === first ? isDigit$2(second) ? 2 : 0 : isDigit$2(first) ? 1 : 0;
}, charCodeDefinitions$p.isUppercaseLetter = isUppercaseLetter$1, charCodeDefinitions$p.isValidEscape = isValidEscape$1, 
charCodeDefinitions$p.isWhiteSpace = isWhiteSpace$1;

var utils$u = {};

const charCodeDefinitions$o = charCodeDefinitions$p;

function getCharCode$1(source, offset) {
  return offset < source.length ? source.charCodeAt(offset) : 0;
}

function getNewlineLength$1(source, offset, code) {
  return 13 === code && 10 === getCharCode$1(source, offset + 1) ? 2 : 1;
}

function cmpChar$1(testStr, offset, referenceCode) {
  let code = testStr.charCodeAt(offset);
  return charCodeDefinitions$o.isUppercaseLetter(code) && (code |= 32), code === referenceCode;
}

function findDecimalNumberEnd$1(source, offset) {
  for (;offset < source.length && charCodeDefinitions$o.isDigit(source.charCodeAt(offset)); offset++) ;
  return offset;
}

function consumeEscaped$1(source, offset) {
  if (offset += 2, charCodeDefinitions$o.isHexDigit(getCharCode$1(source, offset - 1))) {
    for (const maxOffset = Math.min(source.length, offset + 5); offset < maxOffset && charCodeDefinitions$o.isHexDigit(getCharCode$1(source, offset)); offset++) ;
    const code = getCharCode$1(source, offset);
    charCodeDefinitions$o.isWhiteSpace(code) && (offset += getNewlineLength$1(source, offset, code));
  }
  return offset;
}

utils$u.cmpChar = cmpChar$1, utils$u.cmpStr = function(testStr, start, end, referenceStr) {
  if (end - start !== referenceStr.length) return !1;
  if (start < 0 || end > testStr.length) return !1;
  for (let i = start; i < end; i++) {
    const referenceCode = referenceStr.charCodeAt(i - start);
    let testCode = testStr.charCodeAt(i);
    if (charCodeDefinitions$o.isUppercaseLetter(testCode) && (testCode |= 32), testCode !== referenceCode) return !1;
  }
  return !0;
}, utils$u.consumeBadUrlRemnants = function(source, offset) {
  for (;offset < source.length; offset++) {
    const code = source.charCodeAt(offset);
    if (0x0029 === code) {
      offset++;
      break;
    }
    charCodeDefinitions$o.isValidEscape(code, getCharCode$1(source, offset + 1)) && (offset = consumeEscaped$1(source, offset));
  }
  return offset;
}, utils$u.consumeEscaped = consumeEscaped$1, utils$u.consumeName = function(source, offset) {
  for (;offset < source.length; offset++) {
    const code = source.charCodeAt(offset);
    if (!charCodeDefinitions$o.isName(code)) {
      if (!charCodeDefinitions$o.isValidEscape(code, getCharCode$1(source, offset + 1))) break;
      offset = consumeEscaped$1(source, offset) - 1;
    }
  }
  return offset;
}, utils$u.consumeNumber = function(source, offset) {
  let code = source.charCodeAt(offset);
  if (0x002B !== code && 0x002D !== code || (code = source.charCodeAt(offset += 1)), 
  charCodeDefinitions$o.isDigit(code) && (offset = findDecimalNumberEnd$1(source, offset + 1), 
  code = source.charCodeAt(offset)), 0x002E === code && charCodeDefinitions$o.isDigit(source.charCodeAt(offset + 1)) && (offset = findDecimalNumberEnd$1(source, offset += 2)), 
  cmpChar$1(source, offset, 101)) {
    let sign = 0;
    code = source.charCodeAt(offset + 1), 0x002D !== code && 0x002B !== code || (sign = 1, 
    code = source.charCodeAt(offset + 2)), charCodeDefinitions$o.isDigit(code) && (offset = findDecimalNumberEnd$1(source, offset + 1 + sign + 1));
  }
  return offset;
}, utils$u.decodeEscaped = function(escaped) {
  if (1 === escaped.length && !charCodeDefinitions$o.isHexDigit(escaped.charCodeAt(0))) return escaped[0];
  let code = parseInt(escaped, 16);
  return (0 === code || code >= 0xD800 && code <= 0xDFFF || code > 0x10FFFF) && (code = 0xFFFD), 
  String.fromCodePoint(code);
}, utils$u.findDecimalNumberEnd = findDecimalNumberEnd$1, utils$u.findWhiteSpaceEnd = function(source, offset) {
  for (;offset < source.length && charCodeDefinitions$o.isWhiteSpace(source.charCodeAt(offset)); offset++) ;
  return offset;
}, utils$u.findWhiteSpaceStart = function(source, offset) {
  for (;offset >= 0 && charCodeDefinitions$o.isWhiteSpace(source.charCodeAt(offset)); offset--) ;
  return offset + 1;
}, utils$u.getNewlineLength = getNewlineLength$1;

var names$g = [ "EOF-token", "ident-token", "function-token", "at-keyword-token", "hash-token", "string-token", "bad-string-token", "url-token", "bad-url-token", "delim-token", "number-token", "percentage-token", "dimension-token", "whitespace-token", "CDO-token", "CDC-token", "colon-token", "semicolon-token", "comma-token", "[-token", "]-token", "(-token", ")-token", "{-token", "}-token" ], OffsetToLocation$7 = {}, adoptBuffer$7 = {};

adoptBuffer$7.adoptBuffer = function(buffer = null, size) {
  return null === buffer || buffer.length < size ? new Uint32Array(Math.max(size + 1024, 16384)) : buffer;
};

const adoptBuffer$5 = adoptBuffer$7, charCodeDefinitions$n = charCodeDefinitions$p;

function computeLinesAndColumns$1(host) {
  const source = host.source, sourceLength = source.length, startOffset = source.length > 0 ? charCodeDefinitions$n.isBOM(source.charCodeAt(0)) : 0, lines = adoptBuffer$5.adoptBuffer(host.lines, sourceLength), columns = adoptBuffer$5.adoptBuffer(host.columns, sourceLength);
  let line = host.startLine, column = host.startColumn;
  for (let i = startOffset; i < sourceLength; i++) {
    const code = source.charCodeAt(i);
    lines[i] = line, columns[i] = column++, 10 !== code && 13 !== code && 12 !== code || (13 === code && i + 1 < sourceLength && 10 === source.charCodeAt(i + 1) && (i++, 
    lines[i] = line, columns[i] = column), line++, column = 1);
  }
  lines[sourceLength] = line, columns[sourceLength] = column, host.lines = lines, 
  host.columns = columns, host.computed = !0;
}

OffsetToLocation$7.OffsetToLocation = class {
  constructor() {
    this.lines = null, this.columns = null, this.computed = !1;
  }
  setSource(source, startOffset = 0, startLine = 1, startColumn = 1) {
    this.source = source, this.startOffset = startOffset, this.startLine = startLine, 
    this.startColumn = startColumn, this.computed = !1;
  }
  getLocation(offset, filename) {
    return this.computed || computeLinesAndColumns$1(this), {
      source: filename,
      offset: this.startOffset + offset,
      line: this.lines[offset],
      column: this.columns[offset]
    };
  }
  getLocationRange(start, end, filename) {
    return this.computed || computeLinesAndColumns$1(this), {
      source: filename,
      start: {
        offset: this.startOffset + start,
        line: this.lines[start],
        column: this.columns[start]
      },
      end: {
        offset: this.startOffset + end,
        line: this.lines[end],
        column: this.columns[end]
      }
    };
  }
};

var TokenStream$9 = {};

const adoptBuffer$4 = adoptBuffer$7, utils$t = utils$u, names$f = names$g, types$1H = types$1I, balancePair$3 = new Map([ [ types$1H.Function, types$1H.RightParenthesis ], [ types$1H.LeftParenthesis, types$1H.RightParenthesis ], [ types$1H.LeftSquareBracket, types$1H.RightSquareBracket ], [ types$1H.LeftCurlyBracket, types$1H.RightCurlyBracket ] ]);

TokenStream$9.TokenStream = class {
  constructor(source, tokenize) {
    this.setSource(source, tokenize);
  }
  reset() {
    this.eof = !1, this.tokenIndex = -1, this.tokenType = 0, this.tokenStart = this.firstCharOffset, 
    this.tokenEnd = this.firstCharOffset;
  }
  setSource(source = "", tokenize = (() => {})) {
    const sourceLength = (source = String(source || "")).length, offsetAndType = adoptBuffer$4.adoptBuffer(this.offsetAndType, source.length + 1), balance = adoptBuffer$4.adoptBuffer(this.balance, source.length + 1);
    let tokenCount = 0, balanceCloseType = 0, balanceStart = 0, firstCharOffset = -1;
    for (this.offsetAndType = null, this.balance = null, tokenize(source, ((type, start, end) => {
      switch (type) {
       default:
        balance[tokenCount] = sourceLength;
        break;

       case balanceCloseType:
        {
          let balancePrev = 16777215 & balanceStart;
          for (balanceStart = balance[balancePrev], balanceCloseType = balanceStart >> 24, 
          balance[tokenCount] = balancePrev, balance[balancePrev++] = tokenCount; balancePrev < tokenCount; balancePrev++) balance[balancePrev] === sourceLength && (balance[balancePrev] = tokenCount);
          break;
        }

       case types$1H.LeftParenthesis:
       case types$1H.Function:
       case types$1H.LeftSquareBracket:
       case types$1H.LeftCurlyBracket:
        balance[tokenCount] = balanceStart, balanceCloseType = balancePair$3.get(type), 
        balanceStart = balanceCloseType << 24 | tokenCount;
      }
      offsetAndType[tokenCount++] = type << 24 | end, -1 === firstCharOffset && (firstCharOffset = start);
    })), offsetAndType[tokenCount] = types$1H.EOF << 24 | sourceLength, balance[tokenCount] = sourceLength, 
    balance[sourceLength] = sourceLength; 0 !== balanceStart; ) {
      const balancePrev = 16777215 & balanceStart;
      balanceStart = balance[balancePrev], balance[balancePrev] = sourceLength;
    }
    this.source = source, this.firstCharOffset = -1 === firstCharOffset ? 0 : firstCharOffset, 
    this.tokenCount = tokenCount, this.offsetAndType = offsetAndType, this.balance = balance, 
    this.reset(), this.next();
  }
  lookupType(offset) {
    return (offset += this.tokenIndex) < this.tokenCount ? this.offsetAndType[offset] >> 24 : types$1H.EOF;
  }
  lookupOffset(offset) {
    return (offset += this.tokenIndex) < this.tokenCount ? 16777215 & this.offsetAndType[offset - 1] : this.source.length;
  }
  lookupValue(offset, referenceStr) {
    return (offset += this.tokenIndex) < this.tokenCount && utils$t.cmpStr(this.source, 16777215 & this.offsetAndType[offset - 1], 16777215 & this.offsetAndType[offset], referenceStr);
  }
  getTokenStart(tokenIndex) {
    return tokenIndex === this.tokenIndex ? this.tokenStart : tokenIndex > 0 ? tokenIndex < this.tokenCount ? 16777215 & this.offsetAndType[tokenIndex - 1] : 16777215 & this.offsetAndType[this.tokenCount] : this.firstCharOffset;
  }
  substrToCursor(start) {
    return this.source.substring(start, this.tokenStart);
  }
  isBalanceEdge(pos) {
    return this.balance[this.tokenIndex] < pos;
  }
  isDelim(code, offset) {
    return offset ? this.lookupType(offset) === types$1H.Delim && this.source.charCodeAt(this.lookupOffset(offset)) === code : this.tokenType === types$1H.Delim && this.source.charCodeAt(this.tokenStart) === code;
  }
  skip(tokenCount) {
    let next = this.tokenIndex + tokenCount;
    next < this.tokenCount ? (this.tokenIndex = next, this.tokenStart = 16777215 & this.offsetAndType[next - 1], 
    next = this.offsetAndType[next], this.tokenType = next >> 24, this.tokenEnd = 16777215 & next) : (this.tokenIndex = this.tokenCount, 
    this.next());
  }
  next() {
    let next = this.tokenIndex + 1;
    next < this.tokenCount ? (this.tokenIndex = next, this.tokenStart = this.tokenEnd, 
    next = this.offsetAndType[next], this.tokenType = next >> 24, this.tokenEnd = 16777215 & next) : (this.eof = !0, 
    this.tokenIndex = this.tokenCount, this.tokenType = types$1H.EOF, this.tokenStart = this.tokenEnd = this.source.length);
  }
  skipSC() {
    for (;this.tokenType === types$1H.WhiteSpace || this.tokenType === types$1H.Comment; ) this.next();
  }
  skipUntilBalanced(startToken, stopConsume) {
    let balanceEnd, offset, cursor = startToken;
    loop: for (;cursor < this.tokenCount && (balanceEnd = this.balance[cursor], !(balanceEnd < startToken)); cursor++) switch (offset = cursor > 0 ? 16777215 & this.offsetAndType[cursor - 1] : this.firstCharOffset, 
    stopConsume(this.source.charCodeAt(offset))) {
     case 1:
      break loop;

     case 2:
      cursor++;
      break loop;

     default:
      this.balance[balanceEnd] === cursor && (cursor = balanceEnd);
    }
    this.skip(cursor - this.tokenIndex);
  }
  forEachToken(fn) {
    for (let i = 0, offset = this.firstCharOffset; i < this.tokenCount; i++) {
      const start = offset, item = this.offsetAndType[i], end = 16777215 & item;
      offset = end, fn(item >> 24, start, end, i);
    }
  }
  dump() {
    const tokens = new Array(this.tokenCount);
    return this.forEachToken(((type, start, end, index) => {
      tokens[index] = {
        idx: index,
        type: names$f[type],
        chunk: this.source.substring(start, end),
        balance: this.balance[index]
      };
    })), tokens;
  }
};

const types$1G = types$1I, charCodeDefinitions$m = charCodeDefinitions$p, utils$s = utils$u, names$e = names$g, OffsetToLocation$5 = OffsetToLocation$7, TokenStream$7 = TokenStream$9;

tokenizer$5.AtKeyword = types$1G.AtKeyword, tokenizer$5.BadString = types$1G.BadString, 
tokenizer$5.BadUrl = types$1G.BadUrl, tokenizer$5.CDC = types$1G.CDC, tokenizer$5.CDO = types$1G.CDO, 
tokenizer$5.Colon = types$1G.Colon, tokenizer$5.Comma = types$1G.Comma, tokenizer$5.Comment = types$1G.Comment, 
tokenizer$5.Delim = types$1G.Delim, tokenizer$5.Dimension = types$1G.Dimension, 
tokenizer$5.EOF = types$1G.EOF, tokenizer$5.Function = types$1G.Function, tokenizer$5.Hash = types$1G.Hash, 
tokenizer$5.Ident = types$1G.Ident, tokenizer$5.LeftCurlyBracket = types$1G.LeftCurlyBracket, 
tokenizer$5.LeftParenthesis = types$1G.LeftParenthesis, tokenizer$5.LeftSquareBracket = types$1G.LeftSquareBracket, 
tokenizer$5.Number = types$1G.Number, tokenizer$5.Percentage = types$1G.Percentage, 
tokenizer$5.RightCurlyBracket = types$1G.RightCurlyBracket, tokenizer$5.RightParenthesis = types$1G.RightParenthesis, 
tokenizer$5.RightSquareBracket = types$1G.RightSquareBracket, tokenizer$5.Semicolon = types$1G.Semicolon, 
tokenizer$5.String = types$1G.String, tokenizer$5.Url = types$1G.Url, tokenizer$5.WhiteSpace = types$1G.WhiteSpace, 
tokenizer$5.tokenTypes = types$1G, tokenizer$5.DigitCategory = charCodeDefinitions$m.DigitCategory, 
tokenizer$5.EofCategory = charCodeDefinitions$m.EofCategory, tokenizer$5.NameStartCategory = charCodeDefinitions$m.NameStartCategory, 
tokenizer$5.NonPrintableCategory = charCodeDefinitions$m.NonPrintableCategory, tokenizer$5.WhiteSpaceCategory = charCodeDefinitions$m.WhiteSpaceCategory, 
tokenizer$5.charCodeCategory = charCodeDefinitions$m.charCodeCategory, tokenizer$5.isBOM = charCodeDefinitions$m.isBOM, 
tokenizer$5.isDigit = charCodeDefinitions$m.isDigit, tokenizer$5.isHexDigit = charCodeDefinitions$m.isHexDigit, 
tokenizer$5.isIdentifierStart = charCodeDefinitions$m.isIdentifierStart, tokenizer$5.isLetter = charCodeDefinitions$m.isLetter, 
tokenizer$5.isLowercaseLetter = charCodeDefinitions$m.isLowercaseLetter, tokenizer$5.isName = charCodeDefinitions$m.isName, 
tokenizer$5.isNameStart = charCodeDefinitions$m.isNameStart, tokenizer$5.isNewline = charCodeDefinitions$m.isNewline, 
tokenizer$5.isNonAscii = charCodeDefinitions$m.isNonAscii, tokenizer$5.isNonPrintable = charCodeDefinitions$m.isNonPrintable, 
tokenizer$5.isNumberStart = charCodeDefinitions$m.isNumberStart, tokenizer$5.isUppercaseLetter = charCodeDefinitions$m.isUppercaseLetter, 
tokenizer$5.isValidEscape = charCodeDefinitions$m.isValidEscape, tokenizer$5.isWhiteSpace = charCodeDefinitions$m.isWhiteSpace, 
tokenizer$5.cmpChar = utils$s.cmpChar, tokenizer$5.cmpStr = utils$s.cmpStr, tokenizer$5.consumeBadUrlRemnants = utils$s.consumeBadUrlRemnants, 
tokenizer$5.consumeEscaped = utils$s.consumeEscaped, tokenizer$5.consumeName = utils$s.consumeName, 
tokenizer$5.consumeNumber = utils$s.consumeNumber, tokenizer$5.decodeEscaped = utils$s.decodeEscaped, 
tokenizer$5.findDecimalNumberEnd = utils$s.findDecimalNumberEnd, tokenizer$5.findWhiteSpaceEnd = utils$s.findWhiteSpaceEnd, 
tokenizer$5.findWhiteSpaceStart = utils$s.findWhiteSpaceStart, tokenizer$5.getNewlineLength = utils$s.getNewlineLength, 
tokenizer$5.tokenNames = names$e, tokenizer$5.OffsetToLocation = OffsetToLocation$5.OffsetToLocation, 
tokenizer$5.TokenStream = TokenStream$7.TokenStream, tokenizer$5.tokenize = function(source, onToken) {
  function getCharCode(offset) {
    return offset < sourceLength ? source.charCodeAt(offset) : 0;
  }
  function consumeNumericToken() {
    return offset = utils$s.consumeNumber(source, offset), charCodeDefinitions$m.isIdentifierStart(getCharCode(offset), getCharCode(offset + 1), getCharCode(offset + 2)) ? (type = types$1G.Dimension, 
    void (offset = utils$s.consumeName(source, offset))) : 0x0025 === getCharCode(offset) ? (type = types$1G.Percentage, 
    void offset++) : void (type = types$1G.Number);
  }
  function consumeIdentLikeToken() {
    const nameStartOffset = offset;
    return offset = utils$s.consumeName(source, offset), utils$s.cmpStr(source, nameStartOffset, offset, "url") && 0x0028 === getCharCode(offset) ? (offset = utils$s.findWhiteSpaceEnd(source, offset + 1), 
    0x0022 === getCharCode(offset) || 0x0027 === getCharCode(offset) ? (type = types$1G.Function, 
    void (offset = nameStartOffset + 4)) : void function() {
      for (type = types$1G.Url, offset = utils$s.findWhiteSpaceEnd(source, offset); offset < source.length; offset++) {
        const code = source.charCodeAt(offset);
        switch (charCodeDefinitions$m.charCodeCategory(code)) {
         case 0x0029:
          return void offset++;

         case charCodeDefinitions$m.WhiteSpaceCategory:
          return offset = utils$s.findWhiteSpaceEnd(source, offset), 0x0029 === getCharCode(offset) || offset >= source.length ? void (offset < source.length && offset++) : (offset = utils$s.consumeBadUrlRemnants(source, offset), 
          void (type = types$1G.BadUrl));

         case 0x0022:
         case 0x0027:
         case 0x0028:
         case charCodeDefinitions$m.NonPrintableCategory:
          return offset = utils$s.consumeBadUrlRemnants(source, offset), void (type = types$1G.BadUrl);

         case 0x005C:
          if (charCodeDefinitions$m.isValidEscape(code, getCharCode(offset + 1))) {
            offset = utils$s.consumeEscaped(source, offset) - 1;
            break;
          }
          return offset = utils$s.consumeBadUrlRemnants(source, offset), void (type = types$1G.BadUrl);
        }
      }
    }()) : 0x0028 === getCharCode(offset) ? (type = types$1G.Function, void offset++) : void (type = types$1G.Ident);
  }
  function consumeStringToken(endingCodePoint) {
    for (endingCodePoint || (endingCodePoint = getCharCode(offset++)), type = types$1G.String; offset < source.length; offset++) {
      const code = source.charCodeAt(offset);
      switch (charCodeDefinitions$m.charCodeCategory(code)) {
       case endingCodePoint:
        return void offset++;

       case charCodeDefinitions$m.WhiteSpaceCategory:
        if (charCodeDefinitions$m.isNewline(code)) return offset += utils$s.getNewlineLength(source, offset, code), 
        void (type = types$1G.BadString);
        break;

       case 0x005C:
        if (offset === source.length - 1) break;
        const nextCode = getCharCode(offset + 1);
        charCodeDefinitions$m.isNewline(nextCode) ? offset += utils$s.getNewlineLength(source, offset + 1, nextCode) : charCodeDefinitions$m.isValidEscape(code, nextCode) && (offset = utils$s.consumeEscaped(source, offset) - 1);
      }
    }
  }
  const sourceLength = (source = String(source || "")).length;
  let type, start = charCodeDefinitions$m.isBOM(getCharCode(0)), offset = start;
  for (;offset < sourceLength; ) {
    const code = source.charCodeAt(offset);
    switch (charCodeDefinitions$m.charCodeCategory(code)) {
     case charCodeDefinitions$m.WhiteSpaceCategory:
      type = types$1G.WhiteSpace, offset = utils$s.findWhiteSpaceEnd(source, offset + 1);
      break;

     case 0x0022:
      consumeStringToken();
      break;

     case 0x0023:
      charCodeDefinitions$m.isName(getCharCode(offset + 1)) || charCodeDefinitions$m.isValidEscape(getCharCode(offset + 1), getCharCode(offset + 2)) ? (type = types$1G.Hash, 
      offset = utils$s.consumeName(source, offset + 1)) : (type = types$1G.Delim, offset++);
      break;

     case 0x0027:
      consumeStringToken();
      break;

     case 0x0028:
      type = types$1G.LeftParenthesis, offset++;
      break;

     case 0x0029:
      type = types$1G.RightParenthesis, offset++;
      break;

     case 0x002B:
      charCodeDefinitions$m.isNumberStart(code, getCharCode(offset + 1), getCharCode(offset + 2)) ? consumeNumericToken() : (type = types$1G.Delim, 
      offset++);
      break;

     case 0x002C:
      type = types$1G.Comma, offset++;
      break;

     case 0x002D:
      charCodeDefinitions$m.isNumberStart(code, getCharCode(offset + 1), getCharCode(offset + 2)) ? consumeNumericToken() : 0x002D === getCharCode(offset + 1) && 0x003E === getCharCode(offset + 2) ? (type = types$1G.CDC, 
      offset += 3) : charCodeDefinitions$m.isIdentifierStart(code, getCharCode(offset + 1), getCharCode(offset + 2)) ? consumeIdentLikeToken() : (type = types$1G.Delim, 
      offset++);
      break;

     case 0x002E:
      charCodeDefinitions$m.isNumberStart(code, getCharCode(offset + 1), getCharCode(offset + 2)) ? consumeNumericToken() : (type = types$1G.Delim, 
      offset++);
      break;

     case 0x002F:
      0x002A === getCharCode(offset + 1) ? (type = types$1G.Comment, offset = source.indexOf("*/", offset + 2), 
      offset = -1 === offset ? source.length : offset + 2) : (type = types$1G.Delim, offset++);
      break;

     case 0x003A:
      type = types$1G.Colon, offset++;
      break;

     case 0x003B:
      type = types$1G.Semicolon, offset++;
      break;

     case 0x003C:
      0x0021 === getCharCode(offset + 1) && 0x002D === getCharCode(offset + 2) && 0x002D === getCharCode(offset + 3) ? (type = types$1G.CDO, 
      offset += 4) : (type = types$1G.Delim, offset++);
      break;

     case 0x0040:
      charCodeDefinitions$m.isIdentifierStart(getCharCode(offset + 1), getCharCode(offset + 2), getCharCode(offset + 3)) ? (type = types$1G.AtKeyword, 
      offset = utils$s.consumeName(source, offset + 1)) : (type = types$1G.Delim, offset++);
      break;

     case 0x005B:
      type = types$1G.LeftSquareBracket, offset++;
      break;

     case 0x005C:
      charCodeDefinitions$m.isValidEscape(code, getCharCode(offset + 1)) ? consumeIdentLikeToken() : (type = types$1G.Delim, 
      offset++);
      break;

     case 0x005D:
      type = types$1G.RightSquareBracket, offset++;
      break;

     case 0x007B:
      type = types$1G.LeftCurlyBracket, offset++;
      break;

     case 0x007D:
      type = types$1G.RightCurlyBracket, offset++;
      break;

     case charCodeDefinitions$m.DigitCategory:
      consumeNumericToken();
      break;

     case charCodeDefinitions$m.NameStartCategory:
      consumeIdentLikeToken();
      break;

     default:
      type = types$1G.Delim, offset++;
    }
    onToken(type, start, start = offset);
  }
};

var create$e = {}, List$f = {};

let releasedCursors$1 = null, List$e = class List {
  static createItem(data) {
    return {
      prev: null,
      next: null,
      data
    };
  }
  constructor() {
    this.head = null, this.tail = null, this.cursor = null;
  }
  createItem(data) {
    return List.createItem(data);
  }
  allocateCursor(prev, next) {
    let cursor;
    return null !== releasedCursors$1 ? (cursor = releasedCursors$1, releasedCursors$1 = releasedCursors$1.cursor, 
    cursor.prev = prev, cursor.next = next, cursor.cursor = this.cursor) : cursor = {
      prev,
      next,
      cursor: this.cursor
    }, this.cursor = cursor, cursor;
  }
  releaseCursor() {
    const {cursor} = this;
    this.cursor = cursor.cursor, cursor.prev = null, cursor.next = null, cursor.cursor = releasedCursors$1, 
    releasedCursors$1 = cursor;
  }
  updateCursors(prevOld, prevNew, nextOld, nextNew) {
    let {cursor} = this;
    for (;null !== cursor; ) cursor.prev === prevOld && (cursor.prev = prevNew), cursor.next === nextOld && (cursor.next = nextNew), 
    cursor = cursor.cursor;
  }
  * [Symbol.iterator]() {
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) yield cursor.data;
  }
  get size() {
    let size = 0;
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) size++;
    return size;
  }
  get isEmpty() {
    return null === this.head;
  }
  get first() {
    return this.head && this.head.data;
  }
  get last() {
    return this.tail && this.tail.data;
  }
  fromArray(array) {
    let cursor = null;
    this.head = null;
    for (let data of array) {
      const item = List.createItem(data);
      null !== cursor ? cursor.next = item : this.head = item, item.prev = cursor, cursor = item;
    }
    return this.tail = cursor, this;
  }
  toArray() {
    return [ ...this ];
  }
  toJSON() {
    return [ ...this ];
  }
  forEach(fn, thisArg = this) {
    const cursor = this.allocateCursor(null, this.head);
    for (;null !== cursor.next; ) {
      const item = cursor.next;
      cursor.next = item.next, fn.call(thisArg, item.data, item, this);
    }
    this.releaseCursor();
  }
  forEachRight(fn, thisArg = this) {
    const cursor = this.allocateCursor(this.tail, null);
    for (;null !== cursor.prev; ) {
      const item = cursor.prev;
      cursor.prev = item.prev, fn.call(thisArg, item.data, item, this);
    }
    this.releaseCursor();
  }
  reduce(fn, initialValue, thisArg = this) {
    let item, cursor = this.allocateCursor(null, this.head), acc = initialValue;
    for (;null !== cursor.next; ) item = cursor.next, cursor.next = item.next, acc = fn.call(thisArg, acc, item.data, item, this);
    return this.releaseCursor(), acc;
  }
  reduceRight(fn, initialValue, thisArg = this) {
    let item, cursor = this.allocateCursor(this.tail, null), acc = initialValue;
    for (;null !== cursor.prev; ) item = cursor.prev, cursor.prev = item.prev, acc = fn.call(thisArg, acc, item.data, item, this);
    return this.releaseCursor(), acc;
  }
  some(fn, thisArg = this) {
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) if (fn.call(thisArg, cursor.data, cursor, this)) return !0;
    return !1;
  }
  map(fn, thisArg = this) {
    const result = new List;
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) result.appendData(fn.call(thisArg, cursor.data, cursor, this));
    return result;
  }
  filter(fn, thisArg = this) {
    const result = new List;
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) fn.call(thisArg, cursor.data, cursor, this) && result.appendData(cursor.data);
    return result;
  }
  nextUntil(start, fn, thisArg = this) {
    if (null === start) return;
    const cursor = this.allocateCursor(null, start);
    for (;null !== cursor.next; ) {
      const item = cursor.next;
      if (cursor.next = item.next, fn.call(thisArg, item.data, item, this)) break;
    }
    this.releaseCursor();
  }
  prevUntil(start, fn, thisArg = this) {
    if (null === start) return;
    const cursor = this.allocateCursor(start, null);
    for (;null !== cursor.prev; ) {
      const item = cursor.prev;
      if (cursor.prev = item.prev, fn.call(thisArg, item.data, item, this)) break;
    }
    this.releaseCursor();
  }
  clear() {
    this.head = null, this.tail = null;
  }
  copy() {
    const result = new List;
    for (let data of this) result.appendData(data);
    return result;
  }
  prepend(item) {
    return this.updateCursors(null, item, this.head, item), null !== this.head ? (this.head.prev = item, 
    item.next = this.head) : this.tail = item, this.head = item, this;
  }
  prependData(data) {
    return this.prepend(List.createItem(data));
  }
  append(item) {
    return this.insert(item);
  }
  appendData(data) {
    return this.insert(List.createItem(data));
  }
  insert(item, before = null) {
    if (null !== before) if (this.updateCursors(before.prev, item, before, item), null === before.prev) {
      if (this.head !== before) throw new Error("before doesn't belong to list");
      this.head = item, before.prev = item, item.next = before, this.updateCursors(null, item);
    } else before.prev.next = item, item.prev = before.prev, before.prev = item, item.next = before; else this.updateCursors(this.tail, item, null, item), 
    null !== this.tail ? (this.tail.next = item, item.prev = this.tail) : this.head = item, 
    this.tail = item;
    return this;
  }
  insertData(data, before) {
    return this.insert(List.createItem(data), before);
  }
  remove(item) {
    if (this.updateCursors(item, item.prev, item, item.next), null !== item.prev) item.prev.next = item.next; else {
      if (this.head !== item) throw new Error("item doesn't belong to list");
      this.head = item.next;
    }
    if (null !== item.next) item.next.prev = item.prev; else {
      if (this.tail !== item) throw new Error("item doesn't belong to list");
      this.tail = item.prev;
    }
    return item.prev = null, item.next = null, item;
  }
  push(data) {
    this.insert(List.createItem(data));
  }
  pop() {
    return null !== this.tail ? this.remove(this.tail) : null;
  }
  unshift(data) {
    this.prepend(List.createItem(data));
  }
  shift() {
    return null !== this.head ? this.remove(this.head) : null;
  }
  prependList(list) {
    return this.insertList(list, this.head);
  }
  appendList(list) {
    return this.insertList(list);
  }
  insertList(list, before) {
    return null === list.head || (null != before ? (this.updateCursors(before.prev, list.tail, before, list.head), 
    null !== before.prev ? (before.prev.next = list.head, list.head.prev = before.prev) : this.head = list.head, 
    before.prev = list.tail, list.tail.next = before) : (this.updateCursors(this.tail, list.tail, null, list.head), 
    null !== this.tail ? (this.tail.next = list.head, list.head.prev = this.tail) : this.head = list.head, 
    this.tail = list.tail), list.head = null, list.tail = null), this;
  }
  replace(oldItem, newItemOrList) {
    "head" in newItemOrList ? this.insertList(newItemOrList, oldItem) : this.insert(newItemOrList, oldItem), 
    this.remove(oldItem);
  }
};

List$f.List = List$e;

var _SyntaxError$3 = {}, createCustomError$9 = {};

createCustomError$9.createCustomError = function(name, message) {
  const error = Object.create(SyntaxError.prototype), errorStack = new Error;
  return Object.assign(error, {
    name,
    message,
    get stack() {
      return (errorStack.stack || "").replace(/^(.+\n){1,3}/, `${name}: ${message}\n`);
    }
  });
};

const createCustomError$7 = createCustomError$9, MAX_LINE_LENGTH$1 = 100, OFFSET_CORRECTION$1 = 60, TAB_REPLACEMENT$1 = "    ";

function sourceFragment$1({source, line, column}, extraLines) {
  function processLines(start, end) {
    return lines.slice(start, end).map(((line, idx) => String(start + idx + 1).padStart(maxNumLength) + " |" + line)).join("\n");
  }
  const lines = source.split(/\r\n?|\n|\f/), startLine = Math.max(1, line - extraLines) - 1, endLine = Math.min(line + extraLines, lines.length + 1), maxNumLength = Math.max(4, String(endLine).length) + 1;
  let cutLeft = 0;
  (column += (TAB_REPLACEMENT$1.length - 1) * (lines[line - 1].substr(0, column - 1).match(/\t/g) || []).length) > MAX_LINE_LENGTH$1 && (cutLeft = column - OFFSET_CORRECTION$1 + 3, 
  column = OFFSET_CORRECTION$1 - 2);
  for (let i = startLine; i <= endLine; i++) i >= 0 && i < lines.length && (lines[i] = lines[i].replace(/\t/g, TAB_REPLACEMENT$1), 
  lines[i] = (cutLeft > 0 && lines[i].length > cutLeft ? "…" : "") + lines[i].substr(cutLeft, MAX_LINE_LENGTH$1 - 2) + (lines[i].length > cutLeft + MAX_LINE_LENGTH$1 - 1 ? "…" : ""));
  return [ processLines(startLine, line), new Array(column + maxNumLength + 2).join("-") + "^", processLines(line, endLine) ].filter(Boolean).join("\n");
}

_SyntaxError$3.SyntaxError = function(message, source, offset, line, column) {
  return Object.assign(createCustomError$7.createCustomError("SyntaxError", message), {
    source,
    offset,
    line,
    column,
    sourceFragment: extraLines => sourceFragment$1({
      source,
      line,
      column
    }, isNaN(extraLines) ? 0 : extraLines),
    get formattedMessage() {
      return `Parse error: ${message}\n` + sourceFragment$1({
        source,
        line,
        column
      }, 2);
    }
  });
};

var sequence$3 = {};

const types$1F = types$1I;

sequence$3.readSequence = function(recognizer) {
  const children = this.createList();
  let space = !1;
  const context = {
    recognizer
  };
  for (;!this.eof; ) {
    switch (this.tokenType) {
     case types$1F.Comment:
      this.next();
      continue;

     case types$1F.WhiteSpace:
      space = !0, this.next();
      continue;
    }
    let child = recognizer.getNode.call(this, context);
    if (void 0 === child) break;
    space && (recognizer.onWhiteSpace && recognizer.onWhiteSpace.call(this, child, children, context), 
    space = !1), children.push(child);
  }
  return space && recognizer.onWhiteSpace && recognizer.onWhiteSpace.call(this, null, children, context), 
  children;
};

const List$d = List$f, SyntaxError$9 = _SyntaxError$3, index$j = tokenizer$5, sequence$2 = sequence$3, OffsetToLocation$4 = OffsetToLocation$7, TokenStream$6 = TokenStream$9, utils$r = utils$u, types$1E = types$1I, names$d = names$g, NOOP$1 = () => {};

function createParseContext$1(name) {
  return function() {
    return this[name]();
  };
}

function fetchParseValues$1(dict) {
  const result = Object.create(null);
  for (const name in dict) {
    const item = dict[name], fn = item.parse || item;
    fn && (result[name] = fn);
  }
  return result;
}

create$e.createParser = function(config) {
  let source = "", filename = "<unknown>", needPositions = !1, onParseError = NOOP$1, onParseErrorThrow = !1;
  const locationMap = new OffsetToLocation$4.OffsetToLocation, parser = Object.assign(new TokenStream$6.TokenStream, function(config) {
    const parseConfig = {
      context: Object.create(null),
      scope: Object.assign(Object.create(null), config.scope),
      atrule: fetchParseValues$1(config.atrule),
      pseudo: fetchParseValues$1(config.pseudo),
      node: fetchParseValues$1(config.node)
    };
    for (const name in config.parseContext) switch (typeof config.parseContext[name]) {
     case "function":
      parseConfig.context[name] = config.parseContext[name];
      break;

     case "string":
      parseConfig.context[name] = createParseContext$1(config.parseContext[name]);
    }
    return {
      config: parseConfig,
      ...parseConfig,
      ...parseConfig.node
    };
  }(config || {}), {
    parseAtrulePrelude: !0,
    parseRulePrelude: !0,
    parseValue: !0,
    parseCustomProperty: !1,
    readSequence: sequence$2.readSequence,
    consumeUntilBalanceEnd: () => 0,
    consumeUntilLeftCurlyBracket: code => 123 === code ? 1 : 0,
    consumeUntilLeftCurlyBracketOrSemicolon: code => 123 === code || 59 === code ? 1 : 0,
    consumeUntilExclamationMarkOrSemicolon: code => 33 === code || 59 === code ? 1 : 0,
    consumeUntilSemicolonIncluded: code => 59 === code ? 2 : 0,
    createList: () => new List$d.List,
    createSingleNodeList: node => (new List$d.List).appendData(node),
    getFirstListNode: list => list && list.first,
    getLastListNode: list => list && list.last,
    parseWithFallback(consumer, fallback) {
      const startToken = this.tokenIndex;
      try {
        return consumer.call(this);
      } catch (e) {
        if (onParseErrorThrow) throw e;
        const fallbackNode = fallback.call(this, startToken);
        return onParseErrorThrow = !0, onParseError(e, fallbackNode), onParseErrorThrow = !1, 
        fallbackNode;
      }
    },
    lookupNonWSType(offset) {
      let type;
      do {
        if (type = this.lookupType(offset++), type !== types$1E.WhiteSpace) return type;
      } while (0 !== type);
      return 0;
    },
    charCodeAt: offset => offset >= 0 && offset < source.length ? source.charCodeAt(offset) : 0,
    substring: (offsetStart, offsetEnd) => source.substring(offsetStart, offsetEnd),
    substrToCursor(start) {
      return this.source.substring(start, this.tokenStart);
    },
    cmpChar: (offset, charCode) => utils$r.cmpChar(source, offset, charCode),
    cmpStr: (offsetStart, offsetEnd, str) => utils$r.cmpStr(source, offsetStart, offsetEnd, str),
    consume(tokenType) {
      const start = this.tokenStart;
      return this.eat(tokenType), this.substrToCursor(start);
    },
    consumeFunctionName() {
      const name = source.substring(this.tokenStart, this.tokenEnd - 1);
      return this.eat(types$1E.Function), name;
    },
    consumeNumber(type) {
      const number = source.substring(this.tokenStart, utils$r.consumeNumber(source, this.tokenStart));
      return this.eat(type), number;
    },
    eat(tokenType) {
      if (this.tokenType !== tokenType) {
        const tokenName = names$d[tokenType].slice(0, -6).replace(/-/g, " ").replace(/^./, (m => m.toUpperCase()));
        let message = `${/[[\](){}]/.test(tokenName) ? `"${tokenName}"` : tokenName} is expected`, offset = this.tokenStart;
        switch (tokenType) {
         case types$1E.Ident:
          this.tokenType === types$1E.Function || this.tokenType === types$1E.Url ? (offset = this.tokenEnd - 1, 
          message = "Identifier is expected but function found") : message = "Identifier is expected";
          break;

         case types$1E.Hash:
          this.isDelim(35) && (this.next(), offset++, message = "Name is expected");
          break;

         case types$1E.Percentage:
          this.tokenType === types$1E.Number && (offset = this.tokenEnd, message = "Percent sign is expected");
        }
        this.error(message, offset);
      }
      this.next();
    },
    eatIdent(name) {
      this.tokenType === types$1E.Ident && !1 !== this.lookupValue(0, name) || this.error(`Identifier "${name}" is expected`), 
      this.next();
    },
    eatDelim(code) {
      this.isDelim(code) || this.error(`Delim "${String.fromCharCode(code)}" is expected`), 
      this.next();
    },
    getLocation: (start, end) => needPositions ? locationMap.getLocationRange(start, end, filename) : null,
    getLocationFromList(list) {
      if (needPositions) {
        const head = this.getFirstListNode(list), tail = this.getLastListNode(list);
        return locationMap.getLocationRange(null !== head ? head.loc.start.offset - locationMap.startOffset : this.tokenStart, null !== tail ? tail.loc.end.offset - locationMap.startOffset : this.tokenStart, filename);
      }
      return null;
    },
    error(message, offset) {
      const location = void 0 !== offset && offset < source.length ? locationMap.getLocation(offset) : this.eof ? locationMap.getLocation(utils$r.findWhiteSpaceStart(source, source.length - 1)) : locationMap.getLocation(this.tokenStart);
      throw new SyntaxError$9.SyntaxError(message || "Unexpected input", source, location.offset, location.line, location.column);
    }
  });
  return Object.assign((function(source_, options) {
    source = source_, options = options || {}, parser.setSource(source, index$j.tokenize), 
    locationMap.setSource(source, options.offset, options.line, options.column), filename = options.filename || "<unknown>", 
    needPositions = Boolean(options.positions), onParseError = "function" == typeof options.onParseError ? options.onParseError : NOOP$1, 
    onParseErrorThrow = !1, parser.parseAtrulePrelude = !("parseAtrulePrelude" in options) || Boolean(options.parseAtrulePrelude), 
    parser.parseRulePrelude = !("parseRulePrelude" in options) || Boolean(options.parseRulePrelude), 
    parser.parseValue = !("parseValue" in options) || Boolean(options.parseValue), parser.parseCustomProperty = "parseCustomProperty" in options && Boolean(options.parseCustomProperty);
    const {context = "default", onComment} = options;
    if (context in parser.context == !1) throw new Error("Unknown context `" + context + "`");
    "function" == typeof onComment && parser.forEachToken(((type, start, end) => {
      if (type === types$1E.Comment) {
        const loc = parser.getLocation(start, end), value = utils$r.cmpStr(source, end - 2, end, "*/") ? source.slice(start + 2, end - 2) : source.slice(start + 2, end);
        onComment(value, loc);
      }
    }));
    const ast = parser.context[context].call(parser, options);
    return parser.eof || parser.error(), ast;
  }), {
    SyntaxError: SyntaxError$9.SyntaxError,
    config: parser.config
  });
};

var create$d = {}, sourceMap$3 = {}, sourceMapGenerator = {}, base64Vlq = {}, base64$1 = {}, intToCharMap = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".split("");

base64$1.encode = function(number) {
  if (0 <= number && number < intToCharMap.length) return intToCharMap[number];
  throw new TypeError("Must be between 0 and 63: " + number);
}, base64$1.decode = function(charCode) {
  return 65 <= charCode && charCode <= 90 ? charCode - 65 : 97 <= charCode && charCode <= 122 ? charCode - 97 + 26 : 48 <= charCode && charCode <= 57 ? charCode - 48 + 52 : 43 == charCode ? 62 : 47 == charCode ? 63 : -1;
};

var base64 = base64$1;

base64Vlq.encode = function(aValue) {
  var digit, encoded = "", vlq = function(aValue) {
    return aValue < 0 ? 1 + (-aValue << 1) : 0 + (aValue << 1);
  }(aValue);
  do {
    digit = 31 & vlq, (vlq >>>= 5) > 0 && (digit |= 32), encoded += base64.encode(digit);
  } while (vlq > 0);
  return encoded;
}, base64Vlq.decode = function(aStr, aIndex, aOutParam) {
  var continuation, digit, aValue, shifted, strLen = aStr.length, result = 0, shift = 0;
  do {
    if (aIndex >= strLen) throw new Error("Expected more digits in base 64 VLQ value.");
    if (-1 === (digit = base64.decode(aStr.charCodeAt(aIndex++)))) throw new Error("Invalid base64 digit: " + aStr.charAt(aIndex - 1));
    continuation = !!(32 & digit), result += (digit &= 31) << shift, shift += 5;
  } while (continuation);
  aOutParam.value = (shifted = (aValue = result) >> 1, 1 == (1 & aValue) ? -shifted : shifted), 
  aOutParam.rest = aIndex;
};

var util$3 = {};

!function(exports) {
  exports.getArg = function(aArgs, aName, aDefaultValue) {
    if (aName in aArgs) return aArgs[aName];
    if (3 === arguments.length) return aDefaultValue;
    throw new Error('"' + aName + '" is a required argument.');
  };
  var urlRegexp = /^(?:([\w+\-.]+):)?\/\/(?:(\w+:\w+)@)?([\w.-]*)(?::(\d+))?(.*)$/, dataUrlRegexp = /^data:.+\,.+$/;
  function urlParse(aUrl) {
    var match = aUrl.match(urlRegexp);
    return match ? {
      scheme: match[1],
      auth: match[2],
      host: match[3],
      port: match[4],
      path: match[5]
    } : null;
  }
  function urlGenerate(aParsedUrl) {
    var url = "";
    return aParsedUrl.scheme && (url += aParsedUrl.scheme + ":"), url += "//", aParsedUrl.auth && (url += aParsedUrl.auth + "@"), 
    aParsedUrl.host && (url += aParsedUrl.host), aParsedUrl.port && (url += ":" + aParsedUrl.port), 
    aParsedUrl.path && (url += aParsedUrl.path), url;
  }
  exports.urlParse = urlParse, exports.urlGenerate = urlGenerate;
  var f, cache, normalize = (f = function(aPath) {
    var path = aPath, url = urlParse(aPath);
    if (url) {
      if (!url.path) return aPath;
      path = url.path;
    }
    for (var isAbsolute = exports.isAbsolute(path), parts = [], start = 0, i = 0; ;) {
      if (start = i, -1 === (i = path.indexOf("/", start))) {
        parts.push(path.slice(start));
        break;
      }
      for (parts.push(path.slice(start, i)); i < path.length && "/" === path[i]; ) i++;
    }
    var part, up = 0;
    for (i = parts.length - 1; i >= 0; i--) "." === (part = parts[i]) ? parts.splice(i, 1) : ".." === part ? up++ : up > 0 && ("" === part ? (parts.splice(i + 1, up), 
    up = 0) : (parts.splice(i, 2), up--));
    return "" === (path = parts.join("/")) && (path = isAbsolute ? "/" : "."), url ? (url.path = path, 
    urlGenerate(url)) : path;
  }, cache = [], function(input) {
    for (var i = 0; i < cache.length; i++) if (cache[i].input === input) {
      var temp = cache[0];
      return cache[0] = cache[i], cache[i] = temp, cache[0].result;
    }
    var result = f(input);
    return cache.unshift({
      input,
      result
    }), cache.length > 32 && cache.pop(), result;
  });
  function join(aRoot, aPath) {
    "" === aRoot && (aRoot = "."), "" === aPath && (aPath = ".");
    var aPathUrl = urlParse(aPath), aRootUrl = urlParse(aRoot);
    if (aRootUrl && (aRoot = aRootUrl.path || "/"), aPathUrl && !aPathUrl.scheme) return aRootUrl && (aPathUrl.scheme = aRootUrl.scheme), 
    urlGenerate(aPathUrl);
    if (aPathUrl || aPath.match(dataUrlRegexp)) return aPath;
    if (aRootUrl && !aRootUrl.host && !aRootUrl.path) return aRootUrl.host = aPath, 
    urlGenerate(aRootUrl);
    var joined = "/" === aPath.charAt(0) ? aPath : normalize(aRoot.replace(/\/+$/, "") + "/" + aPath);
    return aRootUrl ? (aRootUrl.path = joined, urlGenerate(aRootUrl)) : joined;
  }
  exports.normalize = normalize, exports.join = join, exports.isAbsolute = function(aPath) {
    return "/" === aPath.charAt(0) || urlRegexp.test(aPath);
  }, exports.relative = function(aRoot, aPath) {
    "" === aRoot && (aRoot = "."), aRoot = aRoot.replace(/\/$/, "");
    for (var level = 0; 0 !== aPath.indexOf(aRoot + "/"); ) {
      var index = aRoot.lastIndexOf("/");
      if (index < 0) return aPath;
      if ((aRoot = aRoot.slice(0, index)).match(/^([^\/]+:\/)?\/*$/)) return aPath;
      ++level;
    }
    return Array(level + 1).join("../") + aPath.substr(aRoot.length + 1);
  };
  var supportsNullProto = !("__proto__" in Object.create(null));
  function identity(s) {
    return s;
  }
  function isProtoString(s) {
    if (!s) return !1;
    var length = s.length;
    if (length < 9) return !1;
    if (95 !== s.charCodeAt(length - 1) || 95 !== s.charCodeAt(length - 2) || 111 !== s.charCodeAt(length - 3) || 116 !== s.charCodeAt(length - 4) || 111 !== s.charCodeAt(length - 5) || 114 !== s.charCodeAt(length - 6) || 112 !== s.charCodeAt(length - 7) || 95 !== s.charCodeAt(length - 8) || 95 !== s.charCodeAt(length - 9)) return !1;
    for (var i = length - 10; i >= 0; i--) if (36 !== s.charCodeAt(i)) return !1;
    return !0;
  }
  function strcmp(aStr1, aStr2) {
    return aStr1 === aStr2 ? 0 : null === aStr1 ? 1 : null === aStr2 ? -1 : aStr1 > aStr2 ? 1 : -1;
  }
  exports.toSetString = supportsNullProto ? identity : function(aStr) {
    return isProtoString(aStr) ? "$" + aStr : aStr;
  }, exports.fromSetString = supportsNullProto ? identity : function(aStr) {
    return isProtoString(aStr) ? aStr.slice(1) : aStr;
  }, exports.compareByOriginalPositions = function(mappingA, mappingB, onlyCompareOriginal) {
    var cmp = strcmp(mappingA.source, mappingB.source);
    return 0 !== cmp || 0 !== (cmp = mappingA.originalLine - mappingB.originalLine) || 0 !== (cmp = mappingA.originalColumn - mappingB.originalColumn) || onlyCompareOriginal || 0 !== (cmp = mappingA.generatedColumn - mappingB.generatedColumn) || 0 !== (cmp = mappingA.generatedLine - mappingB.generatedLine) ? cmp : strcmp(mappingA.name, mappingB.name);
  }, exports.compareByOriginalPositionsNoSource = function(mappingA, mappingB, onlyCompareOriginal) {
    var cmp;
    return 0 !== (cmp = mappingA.originalLine - mappingB.originalLine) || 0 !== (cmp = mappingA.originalColumn - mappingB.originalColumn) || onlyCompareOriginal || 0 !== (cmp = mappingA.generatedColumn - mappingB.generatedColumn) || 0 !== (cmp = mappingA.generatedLine - mappingB.generatedLine) ? cmp : strcmp(mappingA.name, mappingB.name);
  }, exports.compareByGeneratedPositionsDeflated = function(mappingA, mappingB, onlyCompareGenerated) {
    var cmp = mappingA.generatedLine - mappingB.generatedLine;
    return 0 !== cmp || 0 !== (cmp = mappingA.generatedColumn - mappingB.generatedColumn) || onlyCompareGenerated || 0 !== (cmp = strcmp(mappingA.source, mappingB.source)) || 0 !== (cmp = mappingA.originalLine - mappingB.originalLine) || 0 !== (cmp = mappingA.originalColumn - mappingB.originalColumn) ? cmp : strcmp(mappingA.name, mappingB.name);
  }, exports.compareByGeneratedPositionsDeflatedNoLine = function(mappingA, mappingB, onlyCompareGenerated) {
    var cmp = mappingA.generatedColumn - mappingB.generatedColumn;
    return 0 !== cmp || onlyCompareGenerated || 0 !== (cmp = strcmp(mappingA.source, mappingB.source)) || 0 !== (cmp = mappingA.originalLine - mappingB.originalLine) || 0 !== (cmp = mappingA.originalColumn - mappingB.originalColumn) ? cmp : strcmp(mappingA.name, mappingB.name);
  }, exports.compareByGeneratedPositionsInflated = function(mappingA, mappingB) {
    var cmp = mappingA.generatedLine - mappingB.generatedLine;
    return 0 !== cmp || 0 !== (cmp = mappingA.generatedColumn - mappingB.generatedColumn) || 0 !== (cmp = strcmp(mappingA.source, mappingB.source)) || 0 !== (cmp = mappingA.originalLine - mappingB.originalLine) || 0 !== (cmp = mappingA.originalColumn - mappingB.originalColumn) ? cmp : strcmp(mappingA.name, mappingB.name);
  }, exports.parseSourceMapInput = function(str) {
    return JSON.parse(str.replace(/^\)]}'[^\n]*\n/, ""));
  }, exports.computeSourceURL = function(sourceRoot, sourceURL, sourceMapURL) {
    if (sourceURL = sourceURL || "", sourceRoot && ("/" !== sourceRoot[sourceRoot.length - 1] && "/" !== sourceURL[0] && (sourceRoot += "/"), 
    sourceURL = sourceRoot + sourceURL), sourceMapURL) {
      var parsed = urlParse(sourceMapURL);
      if (!parsed) throw new Error("sourceMapURL could not be parsed");
      if (parsed.path) {
        var index = parsed.path.lastIndexOf("/");
        index >= 0 && (parsed.path = parsed.path.substring(0, index + 1));
      }
      sourceURL = join(urlGenerate(parsed), sourceURL);
    }
    return normalize(sourceURL);
  };
}(util$3);

var arraySet = {}, util$2 = util$3, has = Object.prototype.hasOwnProperty, hasNativeMap = "undefined" != typeof Map;

function ArraySet$1() {
  this._array = [], this._set = hasNativeMap ? new Map : Object.create(null);
}

ArraySet$1.fromArray = function(aArray, aAllowDuplicates) {
  for (var set = new ArraySet$1, i = 0, len = aArray.length; i < len; i++) set.add(aArray[i], aAllowDuplicates);
  return set;
}, ArraySet$1.prototype.size = function() {
  return hasNativeMap ? this._set.size : Object.getOwnPropertyNames(this._set).length;
}, ArraySet$1.prototype.add = function(aStr, aAllowDuplicates) {
  var sStr = hasNativeMap ? aStr : util$2.toSetString(aStr), isDuplicate = hasNativeMap ? this.has(aStr) : has.call(this._set, sStr), idx = this._array.length;
  isDuplicate && !aAllowDuplicates || this._array.push(aStr), isDuplicate || (hasNativeMap ? this._set.set(aStr, idx) : this._set[sStr] = idx);
}, ArraySet$1.prototype.has = function(aStr) {
  if (hasNativeMap) return this._set.has(aStr);
  var sStr = util$2.toSetString(aStr);
  return has.call(this._set, sStr);
}, ArraySet$1.prototype.indexOf = function(aStr) {
  if (hasNativeMap) {
    var idx = this._set.get(aStr);
    if (idx >= 0) return idx;
  } else {
    var sStr = util$2.toSetString(aStr);
    if (has.call(this._set, sStr)) return this._set[sStr];
  }
  throw new Error('"' + aStr + '" is not in the set.');
}, ArraySet$1.prototype.at = function(aIdx) {
  if (aIdx >= 0 && aIdx < this._array.length) return this._array[aIdx];
  throw new Error("No element indexed by " + aIdx);
}, ArraySet$1.prototype.toArray = function() {
  return this._array.slice();
}, arraySet.ArraySet = ArraySet$1;

var mappingList = {}, util$1 = util$3;

function MappingList$1() {
  this._array = [], this._sorted = !0, this._last = {
    generatedLine: -1,
    generatedColumn: 0
  };
}

MappingList$1.prototype.unsortedForEach = function(aCallback, aThisArg) {
  this._array.forEach(aCallback, aThisArg);
}, MappingList$1.prototype.add = function(aMapping) {
  var mappingA, mappingB, lineA, lineB, columnA, columnB;
  mappingA = this._last, mappingB = aMapping, lineA = mappingA.generatedLine, lineB = mappingB.generatedLine, 
  columnA = mappingA.generatedColumn, columnB = mappingB.generatedColumn, lineB > lineA || lineB == lineA && columnB >= columnA || util$1.compareByGeneratedPositionsInflated(mappingA, mappingB) <= 0 ? (this._last = aMapping, 
  this._array.push(aMapping)) : (this._sorted = !1, this._array.push(aMapping));
}, MappingList$1.prototype.toArray = function() {
  return this._sorted || (this._array.sort(util$1.compareByGeneratedPositionsInflated), 
  this._sorted = !0), this._array;
}, mappingList.MappingList = MappingList$1;

var base64VLQ = base64Vlq, util = util$3, ArraySet = arraySet.ArraySet, MappingList = mappingList.MappingList;

function SourceMapGenerator(aArgs) {
  aArgs || (aArgs = {}), this._file = util.getArg(aArgs, "file", null), this._sourceRoot = util.getArg(aArgs, "sourceRoot", null), 
  this._skipValidation = util.getArg(aArgs, "skipValidation", !1), this._sources = new ArraySet, 
  this._names = new ArraySet, this._mappings = new MappingList, this._sourcesContents = null;
}

SourceMapGenerator.prototype._version = 3, SourceMapGenerator.fromSourceMap = function(aSourceMapConsumer) {
  var sourceRoot = aSourceMapConsumer.sourceRoot, generator = new SourceMapGenerator({
    file: aSourceMapConsumer.file,
    sourceRoot
  });
  return aSourceMapConsumer.eachMapping((function(mapping) {
    var newMapping = {
      generated: {
        line: mapping.generatedLine,
        column: mapping.generatedColumn
      }
    };
    null != mapping.source && (newMapping.source = mapping.source, null != sourceRoot && (newMapping.source = util.relative(sourceRoot, newMapping.source)), 
    newMapping.original = {
      line: mapping.originalLine,
      column: mapping.originalColumn
    }, null != mapping.name && (newMapping.name = mapping.name)), generator.addMapping(newMapping);
  })), aSourceMapConsumer.sources.forEach((function(sourceFile) {
    var sourceRelative = sourceFile;
    null !== sourceRoot && (sourceRelative = util.relative(sourceRoot, sourceFile)), 
    generator._sources.has(sourceRelative) || generator._sources.add(sourceRelative);
    var content = aSourceMapConsumer.sourceContentFor(sourceFile);
    null != content && generator.setSourceContent(sourceFile, content);
  })), generator;
}, SourceMapGenerator.prototype.addMapping = function(aArgs) {
  var generated = util.getArg(aArgs, "generated"), original = util.getArg(aArgs, "original", null), source = util.getArg(aArgs, "source", null), name = util.getArg(aArgs, "name", null);
  this._skipValidation || this._validateMapping(generated, original, source, name), 
  null != source && (source = String(source), this._sources.has(source) || this._sources.add(source)), 
  null != name && (name = String(name), this._names.has(name) || this._names.add(name)), 
  this._mappings.add({
    generatedLine: generated.line,
    generatedColumn: generated.column,
    originalLine: null != original && original.line,
    originalColumn: null != original && original.column,
    source,
    name
  });
}, SourceMapGenerator.prototype.setSourceContent = function(aSourceFile, aSourceContent) {
  var source = aSourceFile;
  null != this._sourceRoot && (source = util.relative(this._sourceRoot, source)), 
  null != aSourceContent ? (this._sourcesContents || (this._sourcesContents = Object.create(null)), 
  this._sourcesContents[util.toSetString(source)] = aSourceContent) : this._sourcesContents && (delete this._sourcesContents[util.toSetString(source)], 
  0 === Object.keys(this._sourcesContents).length && (this._sourcesContents = null));
}, SourceMapGenerator.prototype.applySourceMap = function(aSourceMapConsumer, aSourceFile, aSourceMapPath) {
  var sourceFile = aSourceFile;
  if (null == aSourceFile) {
    if (null == aSourceMapConsumer.file) throw new Error('SourceMapGenerator.prototype.applySourceMap requires either an explicit source file, or the source map\'s "file" property. Both were omitted.');
    sourceFile = aSourceMapConsumer.file;
  }
  var sourceRoot = this._sourceRoot;
  null != sourceRoot && (sourceFile = util.relative(sourceRoot, sourceFile));
  var newSources = new ArraySet, newNames = new ArraySet;
  this._mappings.unsortedForEach((function(mapping) {
    if (mapping.source === sourceFile && null != mapping.originalLine) {
      var original = aSourceMapConsumer.originalPositionFor({
        line: mapping.originalLine,
        column: mapping.originalColumn
      });
      null != original.source && (mapping.source = original.source, null != aSourceMapPath && (mapping.source = util.join(aSourceMapPath, mapping.source)), 
      null != sourceRoot && (mapping.source = util.relative(sourceRoot, mapping.source)), 
      mapping.originalLine = original.line, mapping.originalColumn = original.column, 
      null != original.name && (mapping.name = original.name));
    }
    var source = mapping.source;
    null == source || newSources.has(source) || newSources.add(source);
    var name = mapping.name;
    null == name || newNames.has(name) || newNames.add(name);
  }), this), this._sources = newSources, this._names = newNames, aSourceMapConsumer.sources.forEach((function(sourceFile) {
    var content = aSourceMapConsumer.sourceContentFor(sourceFile);
    null != content && (null != aSourceMapPath && (sourceFile = util.join(aSourceMapPath, sourceFile)), 
    null != sourceRoot && (sourceFile = util.relative(sourceRoot, sourceFile)), this.setSourceContent(sourceFile, content));
  }), this);
}, SourceMapGenerator.prototype._validateMapping = function(aGenerated, aOriginal, aSource, aName) {
  if (aOriginal && "number" != typeof aOriginal.line && "number" != typeof aOriginal.column) throw new Error("original.line and original.column are not numbers -- you probably meant to omit the original mapping entirely and only map the generated position. If so, pass null for the original mapping instead of an object with empty or null values.");
  if ((!(aGenerated && "line" in aGenerated && "column" in aGenerated && aGenerated.line > 0 && aGenerated.column >= 0) || aOriginal || aSource || aName) && !(aGenerated && "line" in aGenerated && "column" in aGenerated && aOriginal && "line" in aOriginal && "column" in aOriginal && aGenerated.line > 0 && aGenerated.column >= 0 && aOriginal.line > 0 && aOriginal.column >= 0 && aSource)) throw new Error("Invalid mapping: " + JSON.stringify({
    generated: aGenerated,
    source: aSource,
    original: aOriginal,
    name: aName
  }));
}, SourceMapGenerator.prototype._serializeMappings = function() {
  for (var next, mapping, nameIdx, sourceIdx, previousGeneratedColumn = 0, previousGeneratedLine = 1, previousOriginalColumn = 0, previousOriginalLine = 0, previousName = 0, previousSource = 0, result = "", mappings = this._mappings.toArray(), i = 0, len = mappings.length; i < len; i++) {
    if (next = "", (mapping = mappings[i]).generatedLine !== previousGeneratedLine) for (previousGeneratedColumn = 0; mapping.generatedLine !== previousGeneratedLine; ) next += ";", 
    previousGeneratedLine++; else if (i > 0) {
      if (!util.compareByGeneratedPositionsInflated(mapping, mappings[i - 1])) continue;
      next += ",";
    }
    next += base64VLQ.encode(mapping.generatedColumn - previousGeneratedColumn), previousGeneratedColumn = mapping.generatedColumn, 
    null != mapping.source && (sourceIdx = this._sources.indexOf(mapping.source), next += base64VLQ.encode(sourceIdx - previousSource), 
    previousSource = sourceIdx, next += base64VLQ.encode(mapping.originalLine - 1 - previousOriginalLine), 
    previousOriginalLine = mapping.originalLine - 1, next += base64VLQ.encode(mapping.originalColumn - previousOriginalColumn), 
    previousOriginalColumn = mapping.originalColumn, null != mapping.name && (nameIdx = this._names.indexOf(mapping.name), 
    next += base64VLQ.encode(nameIdx - previousName), previousName = nameIdx)), result += next;
  }
  return result;
}, SourceMapGenerator.prototype._generateSourcesContent = function(aSources, aSourceRoot) {
  return aSources.map((function(source) {
    if (!this._sourcesContents) return null;
    null != aSourceRoot && (source = util.relative(aSourceRoot, source));
    var key = util.toSetString(source);
    return Object.prototype.hasOwnProperty.call(this._sourcesContents, key) ? this._sourcesContents[key] : null;
  }), this);
}, SourceMapGenerator.prototype.toJSON = function() {
  var map = {
    version: this._version,
    sources: this._sources.toArray(),
    names: this._names.toArray(),
    mappings: this._serializeMappings()
  };
  return null != this._file && (map.file = this._file), null != this._sourceRoot && (map.sourceRoot = this._sourceRoot), 
  this._sourcesContents && (map.sourcesContent = this._generateSourcesContent(map.sources, map.sourceRoot)), 
  map;
}, SourceMapGenerator.prototype.toString = function() {
  return JSON.stringify(this.toJSON());
}, sourceMapGenerator.SourceMapGenerator = SourceMapGenerator;

const sourceMapGenerator_js$1 = sourceMapGenerator, trackNodes$1 = new Set([ "Atrule", "Selector", "Declaration" ]);

sourceMap$3.generateSourceMap = function(handlers) {
  const map = new sourceMapGenerator_js$1.SourceMapGenerator, generated = {
    line: 1,
    column: 0
  }, original = {
    line: 0,
    column: 0
  }, activatedGenerated = {
    line: 1,
    column: 0
  }, activatedMapping = {
    generated: activatedGenerated
  };
  let line = 1, column = 0, sourceMappingActive = !1;
  const origHandlersNode = handlers.node;
  handlers.node = function(node) {
    if (node.loc && node.loc.start && trackNodes$1.has(node.type)) {
      const nodeLine = node.loc.start.line, nodeColumn = node.loc.start.column - 1;
      original.line === nodeLine && original.column === nodeColumn || (original.line = nodeLine, 
      original.column = nodeColumn, generated.line = line, generated.column = column, 
      sourceMappingActive && (sourceMappingActive = !1, generated.line === activatedGenerated.line && generated.column === activatedGenerated.column || map.addMapping(activatedMapping)), 
      sourceMappingActive = !0, map.addMapping({
        source: node.loc.source,
        original,
        generated
      }));
    }
    origHandlersNode.call(this, node), sourceMappingActive && trackNodes$1.has(node.type) && (activatedGenerated.line = line, 
    activatedGenerated.column = column);
  };
  const origHandlersEmit = handlers.emit;
  handlers.emit = function(value, type, auto) {
    for (let i = 0; i < value.length; i++) 10 === value.charCodeAt(i) ? (line++, column = 0) : column++;
    origHandlersEmit(value, type, auto);
  };
  const origHandlersResult = handlers.result;
  return handlers.result = function() {
    return sourceMappingActive && map.addMapping(activatedMapping), {
      css: origHandlersResult(),
      map
    };
  }, handlers;
};

var tokenBefore$3 = {};

const types$1D = types$1I, code$1 = (type, value) => {
  if (type === types$1D.Delim && (type = value), "string" == typeof type) {
    const charCode = type.charCodeAt(0);
    return charCode > 0x7F ? 0x8000 : charCode << 8;
  }
  return type;
}, specPairs$1 = [ [ types$1D.Ident, types$1D.Ident ], [ types$1D.Ident, types$1D.Function ], [ types$1D.Ident, types$1D.Url ], [ types$1D.Ident, types$1D.BadUrl ], [ types$1D.Ident, "-" ], [ types$1D.Ident, types$1D.Number ], [ types$1D.Ident, types$1D.Percentage ], [ types$1D.Ident, types$1D.Dimension ], [ types$1D.Ident, types$1D.CDC ], [ types$1D.Ident, types$1D.LeftParenthesis ], [ types$1D.AtKeyword, types$1D.Ident ], [ types$1D.AtKeyword, types$1D.Function ], [ types$1D.AtKeyword, types$1D.Url ], [ types$1D.AtKeyword, types$1D.BadUrl ], [ types$1D.AtKeyword, "-" ], [ types$1D.AtKeyword, types$1D.Number ], [ types$1D.AtKeyword, types$1D.Percentage ], [ types$1D.AtKeyword, types$1D.Dimension ], [ types$1D.AtKeyword, types$1D.CDC ], [ types$1D.Hash, types$1D.Ident ], [ types$1D.Hash, types$1D.Function ], [ types$1D.Hash, types$1D.Url ], [ types$1D.Hash, types$1D.BadUrl ], [ types$1D.Hash, "-" ], [ types$1D.Hash, types$1D.Number ], [ types$1D.Hash, types$1D.Percentage ], [ types$1D.Hash, types$1D.Dimension ], [ types$1D.Hash, types$1D.CDC ], [ types$1D.Dimension, types$1D.Ident ], [ types$1D.Dimension, types$1D.Function ], [ types$1D.Dimension, types$1D.Url ], [ types$1D.Dimension, types$1D.BadUrl ], [ types$1D.Dimension, "-" ], [ types$1D.Dimension, types$1D.Number ], [ types$1D.Dimension, types$1D.Percentage ], [ types$1D.Dimension, types$1D.Dimension ], [ types$1D.Dimension, types$1D.CDC ], [ "#", types$1D.Ident ], [ "#", types$1D.Function ], [ "#", types$1D.Url ], [ "#", types$1D.BadUrl ], [ "#", "-" ], [ "#", types$1D.Number ], [ "#", types$1D.Percentage ], [ "#", types$1D.Dimension ], [ "#", types$1D.CDC ], [ "-", types$1D.Ident ], [ "-", types$1D.Function ], [ "-", types$1D.Url ], [ "-", types$1D.BadUrl ], [ "-", "-" ], [ "-", types$1D.Number ], [ "-", types$1D.Percentage ], [ "-", types$1D.Dimension ], [ "-", types$1D.CDC ], [ types$1D.Number, types$1D.Ident ], [ types$1D.Number, types$1D.Function ], [ types$1D.Number, types$1D.Url ], [ types$1D.Number, types$1D.BadUrl ], [ types$1D.Number, types$1D.Number ], [ types$1D.Number, types$1D.Percentage ], [ types$1D.Number, types$1D.Dimension ], [ types$1D.Number, "%" ], [ types$1D.Number, types$1D.CDC ], [ "@", types$1D.Ident ], [ "@", types$1D.Function ], [ "@", types$1D.Url ], [ "@", types$1D.BadUrl ], [ "@", "-" ], [ "@", types$1D.CDC ], [ ".", types$1D.Number ], [ ".", types$1D.Percentage ], [ ".", types$1D.Dimension ], [ "+", types$1D.Number ], [ "+", types$1D.Percentage ], [ "+", types$1D.Dimension ], [ "/", "*" ] ], safePairs$1 = specPairs$1.concat([ [ types$1D.Ident, types$1D.Hash ], [ types$1D.Dimension, types$1D.Hash ], [ types$1D.Hash, types$1D.Hash ], [ types$1D.AtKeyword, types$1D.LeftParenthesis ], [ types$1D.AtKeyword, types$1D.String ], [ types$1D.AtKeyword, types$1D.Colon ], [ types$1D.Percentage, types$1D.Percentage ], [ types$1D.Percentage, types$1D.Dimension ], [ types$1D.Percentage, types$1D.Function ], [ types$1D.Percentage, "-" ], [ types$1D.RightParenthesis, types$1D.Ident ], [ types$1D.RightParenthesis, types$1D.Function ], [ types$1D.RightParenthesis, types$1D.Percentage ], [ types$1D.RightParenthesis, types$1D.Dimension ], [ types$1D.RightParenthesis, types$1D.Hash ], [ types$1D.RightParenthesis, "-" ] ]);

function createMap$1(pairs) {
  const isWhiteSpaceRequired = new Set(pairs.map((([prev, next]) => code$1(prev) << 16 | code$1(next))));
  return function(prevCode, type, value) {
    const nextCode = code$1(type, value), nextCharCode = value.charCodeAt(0);
    return (45 === nextCharCode && type !== types$1D.Ident && type !== types$1D.Function && type !== types$1D.CDC || 43 === nextCharCode ? isWhiteSpaceRequired.has(prevCode << 16 | nextCharCode << 8) : isWhiteSpaceRequired.has(prevCode << 16 | nextCode)) && this.emit(" ", types$1D.WhiteSpace, !0), 
    nextCode;
  };
}

const spec$1 = createMap$1(specPairs$1), safe$2 = createMap$1(safePairs$1);

tokenBefore$3.safe = safe$2, tokenBefore$3.spec = spec$1;

const index$i = tokenizer$5, sourceMap$2 = sourceMap$3, tokenBefore$2 = tokenBefore$3, types$1C = types$1I;

function processChildren$1(node, delimeter) {
  if ("function" != typeof delimeter) node.children.forEach(this.node, this); else {
    let prev = null;
    node.children.forEach((node => {
      null !== prev && delimeter.call(this, prev), this.node(node), prev = node;
    }));
  }
}

function processChunk$1(chunk) {
  index$i.tokenize(chunk, ((type, start, end) => {
    this.token(type, chunk.slice(start, end));
  }));
}

create$d.createGenerator = function(config) {
  const types$1 = new Map;
  for (let name in config.node) {
    const item = config.node[name];
    "function" == typeof (item.generate || item) && types$1.set(name, item.generate || item);
  }
  return function(node, options) {
    let buffer = "", prevCode = 0, handlers = {
      node(node) {
        if (!types$1.has(node.type)) throw new Error("Unknown node type: " + node.type);
        types$1.get(node.type).call(publicApi, node);
      },
      tokenBefore: tokenBefore$2.safe,
      token(type, value) {
        prevCode = this.tokenBefore(prevCode, type, value), this.emit(value, type, !1), 
        type === types$1C.Delim && 92 === value.charCodeAt(0) && this.emit("\n", types$1C.WhiteSpace, !0);
      },
      emit(value) {
        buffer += value;
      },
      result: () => buffer
    };
    options && ("function" == typeof options.decorator && (handlers = options.decorator(handlers)), 
    options.sourceMap && (handlers = sourceMap$2.generateSourceMap(handlers)), options.mode in tokenBefore$2 && (handlers.tokenBefore = tokenBefore$2[options.mode]));
    const publicApi = {
      node: node => handlers.node(node),
      children: processChildren$1,
      token: (type, value) => handlers.token(type, value),
      tokenize: processChunk$1
    };
    return handlers.node(node), handlers.result();
  };
};

var create$c = {};

const List$c = List$f;

create$c.createConvertor = function(walk) {
  return {
    fromPlainObject: ast => (walk(ast, {
      enter(node) {
        node.children && node.children instanceof List$c.List == !1 && (node.children = (new List$c.List).fromArray(node.children));
      }
    }), ast),
    toPlainObject: ast => (walk(ast, {
      leave(node) {
        node.children && node.children instanceof List$c.List && (node.children = node.children.toArray());
      }
    }), ast)
  };
};

var create$b = {};

const {hasOwnProperty: hasOwnProperty$d} = Object.prototype, noop$5 = function() {};

function ensureFunction$3(value) {
  return "function" == typeof value ? value : noop$5;
}

function invokeForType$1(fn, type) {
  return function(node, item, list) {
    node.type === type && fn.call(this, node, item, list);
  };
}

function getWalkersFromStructure$1(name, nodeType) {
  const structure = nodeType.structure, walkers = [];
  for (const key in structure) {
    if (!1 === hasOwnProperty$d.call(structure, key)) continue;
    let fieldTypes = structure[key];
    const walker = {
      name: key,
      type: !1,
      nullable: !1
    };
    Array.isArray(fieldTypes) || (fieldTypes = [ fieldTypes ]);
    for (const fieldType of fieldTypes) null === fieldType ? walker.nullable = !0 : "string" == typeof fieldType ? walker.type = "node" : Array.isArray(fieldType) && (walker.type = "list");
    walker.type && walkers.push(walker);
  }
  return walkers.length ? {
    context: nodeType.walkContext,
    fields: walkers
  } : null;
}

function createTypeIterator$1(config, reverse) {
  const fields = config.fields.slice(), contextName = config.context, useContext = "string" == typeof contextName;
  return reverse && fields.reverse(), function(node, context, walk, walkReducer) {
    let prevContextValue;
    useContext && (prevContextValue = context[contextName], context[contextName] = node);
    for (const field of fields) {
      const ref = node[field.name];
      if (!field.nullable || ref) if ("list" === field.type) {
        if (reverse ? ref.reduceRight(walkReducer, !1) : ref.reduce(walkReducer, !1)) return !0;
      } else if (walk(ref)) return !0;
    }
    useContext && (context[contextName] = prevContextValue);
  };
}

function createFastTraveralMap$1({StyleSheet, Atrule, Rule, Block, DeclarationList}) {
  return {
    Atrule: {
      StyleSheet,
      Atrule,
      Rule,
      Block
    },
    Rule: {
      StyleSheet,
      Atrule,
      Rule,
      Block
    },
    Declaration: {
      StyleSheet,
      Atrule,
      Rule,
      Block,
      DeclarationList
    }
  };
}

create$b.createWalker = function(config) {
  const types = function(config) {
    const types = {};
    for (const name in config.node) if (hasOwnProperty$d.call(config.node, name)) {
      const nodeType = config.node[name];
      if (!nodeType.structure) throw new Error("Missed `structure` field in `" + name + "` node type definition");
      types[name] = getWalkersFromStructure$1(0, nodeType);
    }
    return types;
  }(config), iteratorsNatural = {}, iteratorsReverse = {}, breakWalk = Symbol("break-walk"), skipNode = Symbol("skip-node");
  for (const name in types) hasOwnProperty$d.call(types, name) && null !== types[name] && (iteratorsNatural[name] = createTypeIterator$1(types[name], !1), 
  iteratorsReverse[name] = createTypeIterator$1(types[name], !0));
  const fastTraversalIteratorsNatural = createFastTraveralMap$1(iteratorsNatural), fastTraversalIteratorsReverse = createFastTraveralMap$1(iteratorsReverse), walk = function(root, options) {
    function walkNode(node, item, list) {
      const enterRet = enter.call(context, node, item, list);
      return enterRet === breakWalk || enterRet !== skipNode && (!(!iterators.hasOwnProperty(node.type) || !iterators[node.type](node, context, walkNode, walkReducer)) || leave.call(context, node, item, list) === breakWalk);
    }
    let enter = noop$5, leave = noop$5, iterators = iteratorsNatural, walkReducer = (ret, data, item, list) => ret || walkNode(data, item, list);
    const context = {
      break: breakWalk,
      skip: skipNode,
      root,
      stylesheet: null,
      atrule: null,
      atrulePrelude: null,
      rule: null,
      selector: null,
      block: null,
      declaration: null,
      function: null
    };
    if ("function" == typeof options) enter = options; else if (options && (enter = ensureFunction$3(options.enter), 
    leave = ensureFunction$3(options.leave), options.reverse && (iterators = iteratorsReverse), 
    options.visit)) {
      if (fastTraversalIteratorsNatural.hasOwnProperty(options.visit)) iterators = options.reverse ? fastTraversalIteratorsReverse[options.visit] : fastTraversalIteratorsNatural[options.visit]; else if (!types.hasOwnProperty(options.visit)) throw new Error("Bad value `" + options.visit + "` for `visit` option (should be: " + Object.keys(types).sort().join(", ") + ")");
      enter = invokeForType$1(enter, options.visit), leave = invokeForType$1(leave, options.visit);
    }
    if (enter === noop$5 && leave === noop$5) throw new Error("Neither `enter` nor `leave` walker handler is set or both aren't a function");
    walkNode(root);
  };
  return walk.break = breakWalk, walk.skip = skipNode, walk.find = function(ast, fn) {
    let found = null;
    return walk(ast, (function(node, item, list) {
      if (fn.call(this, node, item, list)) return found = node, breakWalk;
    })), found;
  }, walk.findLast = function(ast, fn) {
    let found = null;
    return walk(ast, {
      reverse: !0,
      enter(node, item, list) {
        if (fn.call(this, node, item, list)) return found = node, breakWalk;
      }
    }), found;
  }, walk.findAll = function(ast, fn) {
    const found = [];
    return walk(ast, (function(node, item, list) {
      fn.call(this, node, item, list) && found.push(node);
    })), found;
  }, walk;
};

var Lexer$7 = {}, error$4 = {}, generate$1u = {};

function noop$4(value) {
  return value;
}

function internalGenerate$1(node, decorate, forceBraces, compact) {
  let result;
  switch (node.type) {
   case "Group":
    result = function(node, decorate, forceBraces, compact) {
      const combinator = " " === node.combinator || compact ? node.combinator : " " + node.combinator + " ", result = node.terms.map((term => internalGenerate$1(term, decorate, forceBraces, compact))).join(combinator);
      return node.explicit || forceBraces ? (compact || "," === result[0] ? "[" : "[ ") + result + (compact ? "]" : " ]") : result;
    }(node, decorate, forceBraces, compact) + (node.disallowEmpty ? "!" : "");
    break;

   case "Multiplier":
    return internalGenerate$1(node.term, decorate, forceBraces, compact) + decorate(function(multiplier) {
      const {min, max, comma} = multiplier;
      return 0 === min && 0 === max ? comma ? "#?" : "*" : 0 === min && 1 === max ? "?" : 1 === min && 0 === max ? comma ? "#" : "+" : 1 === min && 1 === max ? "" : (comma ? "#" : "") + (min === max ? "{" + min + "}" : "{" + min + "," + (0 !== max ? max : "") + "}");
    }(node), node);

   case "Type":
    result = "<" + node.name + (node.opts ? decorate(function(node) {
      if ("Range" === node.type) return " [" + (null === node.min ? "-∞" : node.min) + "," + (null === node.max ? "∞" : node.max) + "]";
      throw new Error("Unknown node type `" + node.type + "`");
    }(node.opts), node.opts) : "") + ">";
    break;

   case "Property":
    result = "<'" + node.name + "'>";
    break;

   case "Keyword":
    result = node.name;
    break;

   case "AtKeyword":
    result = "@" + node.name;
    break;

   case "Function":
    result = node.name + "(";
    break;

   case "String":
   case "Token":
    result = node.value;
    break;

   case "Comma":
    result = ",";
    break;

   default:
    throw new Error("Unknown node type `" + node.type + "`");
  }
  return decorate(result, node);
}

generate$1u.generate = function(node, options) {
  let decorate = noop$4, forceBraces = !1, compact = !1;
  return "function" == typeof options ? decorate = options : options && (forceBraces = Boolean(options.forceBraces), 
  compact = Boolean(options.compact), "function" == typeof options.decorate && (decorate = options.decorate)), 
  internalGenerate$1(node, decorate, forceBraces, compact);
};

const createCustomError$6 = createCustomError$9, generate$1s = generate$1u, defaultLoc$1 = {
  offset: 0,
  line: 1,
  column: 1
};

function fromLoc$1(node, point) {
  const value = node && node.loc && node.loc[point];
  return value ? "line" in value ? buildLoc$1(value) : value : null;
}

function buildLoc$1({offset, line, column}, extra) {
  const loc = {
    offset,
    line,
    column
  };
  if (extra) {
    const lines = extra.split(/\n|\r\n?|\f/);
    loc.offset += extra.length, loc.line += lines.length - 1, loc.column = 1 === lines.length ? loc.column + extra.length : lines.pop().length + 1;
  }
  return loc;
}

error$4.SyntaxMatchError = function(message, syntax, node, matchResult) {
  const error = createCustomError$6.createCustomError("SyntaxMatchError", message), {css, mismatchOffset, mismatchLength, start, end} = function(matchResult, node) {
    const tokens = matchResult.tokens, longestMatch = matchResult.longestMatch, mismatchNode = longestMatch < tokens.length && tokens[longestMatch].node || null, badNode = mismatchNode !== node ? mismatchNode : null;
    let start, end, mismatchOffset = 0, mismatchLength = 0, entries = 0, css = "";
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i].value;
      i === longestMatch && (mismatchLength = token.length, mismatchOffset = css.length), 
      null !== badNode && tokens[i].node === badNode && (i <= longestMatch ? entries++ : entries = 0), 
      css += token;
    }
    return longestMatch === tokens.length || entries > 1 ? (start = fromLoc$1(badNode || node, "end") || buildLoc$1(defaultLoc$1, css), 
    end = buildLoc$1(start)) : (start = fromLoc$1(badNode, "start") || buildLoc$1(fromLoc$1(node, "start") || defaultLoc$1, css.slice(0, mismatchOffset)), 
    end = fromLoc$1(badNode, "end") || buildLoc$1(start, css.substr(mismatchOffset, mismatchLength))), 
    {
      css,
      mismatchOffset,
      mismatchLength,
      start,
      end
    };
  }(matchResult, node);
  return error.rawMessage = message, error.syntax = syntax ? generate$1s.generate(syntax) : "<generic>", 
  error.css = css, error.mismatchOffset = mismatchOffset, error.mismatchLength = mismatchLength, 
  error.message = message + "\n  syntax: " + error.syntax + "\n   value: " + (css || "<empty string>") + "\n  --------" + new Array(error.mismatchOffset + 1).join("-") + "^", 
  Object.assign(error, start), error.loc = {
    source: node && node.loc && node.loc.source || "<unknown>",
    start,
    end
  }, error;
}, error$4.SyntaxReferenceError = function(type, referenceName) {
  const error = createCustomError$6.createCustomError("SyntaxReferenceError", type + (referenceName ? " `" + referenceName + "`" : ""));
  return error.reference = referenceName, error;
};

var names$c = {};

const keywords$1 = new Map, properties$1 = new Map, HYPHENMINUS$c = 45, keyword$1 = function(keyword) {
  if (keywords$1.has(keyword)) return keywords$1.get(keyword);
  const name = keyword.toLowerCase();
  let descriptor = keywords$1.get(name);
  if (void 0 === descriptor) {
    const custom = isCustomProperty$1(name, 0), vendor = custom ? "" : getVendorPrefix$1(name, 0);
    descriptor = Object.freeze({
      basename: name.substr(vendor.length),
      name,
      prefix: vendor,
      vendor,
      custom
    });
  }
  return keywords$1.set(keyword, descriptor), descriptor;
}, property$1 = function(property) {
  if (properties$1.has(property)) return properties$1.get(property);
  let name = property, hack = property[0];
  "/" === hack ? hack = "/" === property[1] ? "//" : "/" : "_" !== hack && "*" !== hack && "$" !== hack && "#" !== hack && "+" !== hack && "&" !== hack && (hack = "");
  const custom = isCustomProperty$1(name, hack.length);
  if (!custom && (name = name.toLowerCase(), properties$1.has(name))) {
    const descriptor = properties$1.get(name);
    return properties$1.set(property, descriptor), descriptor;
  }
  const vendor = custom ? "" : getVendorPrefix$1(name, hack.length), prefix = name.substr(0, hack.length + vendor.length), descriptor = Object.freeze({
    basename: name.substr(prefix.length),
    name: name.substr(hack.length),
    hack,
    vendor,
    prefix,
    custom
  });
  return properties$1.set(property, descriptor), descriptor;
}, vendorPrefix$1 = getVendorPrefix$1;

function isCustomProperty$1(str, offset) {
  return offset = offset || 0, str.length - offset >= 2 && str.charCodeAt(offset) === HYPHENMINUS$c && str.charCodeAt(offset + 1) === HYPHENMINUS$c;
}

function getVendorPrefix$1(str, offset) {
  if (offset = offset || 0, str.length - offset >= 3 && str.charCodeAt(offset) === HYPHENMINUS$c && str.charCodeAt(offset + 1) !== HYPHENMINUS$c) {
    const secondDashIndex = str.indexOf("-", offset + 2);
    if (-1 !== secondDashIndex) return str.substring(offset, secondDashIndex + 1);
  }
  return "";
}

names$c.isCustomProperty = isCustomProperty$1, names$c.keyword = keyword$1, names$c.property = property$1, 
names$c.vendorPrefix = vendorPrefix$1;

var genericConst$5 = {};

genericConst$5.cssWideKeywords = [ "initial", "inherit", "unset", "revert", "revert-layer" ];

var generic$3 = {};

const charCodeDefinitions$l = charCodeDefinitions$p, types$1B = types$1I, utils$q = utils$u, PLUSSIGN$i = 0x002B, HYPHENMINUS$b = 0x002D;

function isDelim$3(token, code) {
  return null !== token && token.type === types$1B.Delim && token.value.charCodeAt(0) === code;
}

function skipSC$1(token, offset, getNextToken) {
  for (;null !== token && (token.type === types$1B.WhiteSpace || token.type === types$1B.Comment); ) token = getNextToken(++offset);
  return offset;
}

function checkInteger$3(token, valueOffset, disallowSign, offset) {
  if (!token) return 0;
  const code = token.value.charCodeAt(valueOffset);
  if (code === PLUSSIGN$i || code === HYPHENMINUS$b) {
    if (disallowSign) return 0;
    valueOffset++;
  }
  for (;valueOffset < token.value.length; valueOffset++) if (!charCodeDefinitions$l.isDigit(token.value.charCodeAt(valueOffset))) return 0;
  return offset + 1;
}

function consumeB$3(token, offset_, getNextToken) {
  let sign = !1, offset = skipSC$1(token, offset_, getNextToken);
  if (null === (token = getNextToken(offset))) return offset_;
  if (token.type !== types$1B.Number) {
    if (!isDelim$3(token, PLUSSIGN$i) && !isDelim$3(token, HYPHENMINUS$b)) return offset_;
    if (sign = !0, offset = skipSC$1(getNextToken(++offset), offset, getNextToken), 
    null === (token = getNextToken(offset)) || token.type !== types$1B.Number) return 0;
  }
  if (!sign) {
    const code = token.value.charCodeAt(0);
    if (code !== PLUSSIGN$i && code !== HYPHENMINUS$b) return 0;
  }
  return checkInteger$3(token, sign ? 0 : 1, sign, offset);
}

var genericAnPlusB$3 = function(token, getNextToken) {
  let offset = 0;
  if (!token) return 0;
  if (token.type === types$1B.Number) return checkInteger$3(token, 0, false, offset);
  if (token.type === types$1B.Ident && token.value.charCodeAt(0) === HYPHENMINUS$b) {
    if (!utils$q.cmpChar(token.value, 1, 110)) return 0;
    switch (token.value.length) {
     case 2:
      return consumeB$3(getNextToken(++offset), offset, getNextToken);

     case 3:
      return token.value.charCodeAt(2) !== HYPHENMINUS$b ? 0 : (offset = skipSC$1(getNextToken(++offset), offset, getNextToken), 
      checkInteger$3(token = getNextToken(offset), 0, true, offset));

     default:
      return token.value.charCodeAt(2) !== HYPHENMINUS$b ? 0 : checkInteger$3(token, 3, true, offset);
    }
  } else if (token.type === types$1B.Ident || isDelim$3(token, PLUSSIGN$i) && getNextToken(offset + 1).type === types$1B.Ident) {
    if (token.type !== types$1B.Ident && (token = getNextToken(++offset)), null === token || !utils$q.cmpChar(token.value, 0, 110)) return 0;
    switch (token.value.length) {
     case 1:
      return consumeB$3(getNextToken(++offset), offset, getNextToken);

     case 2:
      return token.value.charCodeAt(1) !== HYPHENMINUS$b ? 0 : (offset = skipSC$1(getNextToken(++offset), offset, getNextToken), 
      checkInteger$3(token = getNextToken(offset), 0, true, offset));

     default:
      return token.value.charCodeAt(1) !== HYPHENMINUS$b ? 0 : checkInteger$3(token, 2, true, offset);
    }
  } else if (token.type === types$1B.Dimension) {
    let code = token.value.charCodeAt(0), sign = code === PLUSSIGN$i || code === HYPHENMINUS$b ? 1 : 0, i = sign;
    for (;i < token.value.length && charCodeDefinitions$l.isDigit(token.value.charCodeAt(i)); i++) ;
    return i === sign ? 0 : utils$q.cmpChar(token.value, i, 110) ? i + 1 === token.value.length ? consumeB$3(getNextToken(++offset), offset, getNextToken) : token.value.charCodeAt(i + 1) !== HYPHENMINUS$b ? 0 : i + 2 === token.value.length ? (offset = skipSC$1(getNextToken(++offset), offset, getNextToken), 
    checkInteger$3(token = getNextToken(offset), 0, true, offset)) : checkInteger$3(token, i + 2, true, offset) : 0;
  }
  return 0;
};

const charCodeDefinitions$k = charCodeDefinitions$p, types$1A = types$1I, utils$p = utils$u, HYPHENMINUS$a = 0x002D, QUESTIONMARK$5 = 0x003F;

function isDelim$2(token, code) {
  return null !== token && token.type === types$1A.Delim && token.value.charCodeAt(0) === code;
}

function hexSequence$1(token, offset, allowDash) {
  let hexlen = 0;
  for (let pos = offset; pos < token.value.length; pos++) {
    const code = token.value.charCodeAt(pos);
    if (code === HYPHENMINUS$a && allowDash && 0 !== hexlen) return hexSequence$1(token, offset + hexlen + 1, !1), 
    6;
    if (!charCodeDefinitions$k.isHexDigit(code)) return 0;
    if (++hexlen > 6) return 0;
  }
  return hexlen;
}

function withQuestionMarkSequence$1(consumed, length, getNextToken) {
  if (!consumed) return 0;
  for (;isDelim$2(getNextToken(length), QUESTIONMARK$5); ) {
    if (++consumed > 6) return 0;
    length++;
  }
  return length;
}

var genericUrange$3 = function(token, getNextToken) {
  let length = 0;
  if (null === token || token.type !== types$1A.Ident || !utils$p.cmpChar(token.value, 0, 117)) return 0;
  if (null === (token = getNextToken(++length))) return 0;
  if (isDelim$2(token, 43)) return null === (token = getNextToken(++length)) ? 0 : token.type === types$1A.Ident ? withQuestionMarkSequence$1(hexSequence$1(token, 0, !0), ++length, getNextToken) : isDelim$2(token, QUESTIONMARK$5) ? withQuestionMarkSequence$1(1, ++length, getNextToken) : 0;
  if (token.type === types$1A.Number) {
    const consumedHexLength = hexSequence$1(token, 1, !0);
    return 0 === consumedHexLength ? 0 : null === (token = getNextToken(++length)) ? length : token.type === types$1A.Dimension || token.type === types$1A.Number ? function(token, code) {
      return token.value.charCodeAt(0) === code;
    }(token, HYPHENMINUS$a) && hexSequence$1(token, 1, !1) ? length + 1 : 0 : withQuestionMarkSequence$1(consumedHexLength, length, getNextToken);
  }
  return token.type === types$1A.Dimension ? withQuestionMarkSequence$1(hexSequence$1(token, 1, !0), ++length, getNextToken) : 0;
};

const genericConst$4 = genericConst$5, genericAnPlusB$2 = genericAnPlusB$3, genericUrange$2 = genericUrange$3, types$1z = types$1I, charCodeDefinitions$j = charCodeDefinitions$p, utils$o = utils$u, calcFunctionNames$1 = [ "calc(", "-moz-calc(", "-webkit-calc(" ], balancePair$2 = new Map([ [ types$1z.Function, types$1z.RightParenthesis ], [ types$1z.LeftParenthesis, types$1z.RightParenthesis ], [ types$1z.LeftSquareBracket, types$1z.RightSquareBracket ], [ types$1z.LeftCurlyBracket, types$1z.RightCurlyBracket ] ]);

function charCodeAt$1(str, index) {
  return index < str.length ? str.charCodeAt(index) : 0;
}

function eqStr$1(actual, expected) {
  return utils$o.cmpStr(actual, 0, actual.length, expected);
}

function eqStrAny$1(actual, expected) {
  for (let i = 0; i < expected.length; i++) if (eqStr$1(actual, expected[i])) return !0;
  return !1;
}

function isPostfixIeHack$1(str, offset) {
  return offset === str.length - 2 && (0x005C === charCodeAt$1(str, offset) && charCodeDefinitions$j.isDigit(charCodeAt$1(str, offset + 1)));
}

function outOfRange$1(opts, value, numEnd) {
  if (opts && "Range" === opts.type) {
    const num = Number(void 0 !== numEnd && numEnd !== value.length ? value.substr(0, numEnd) : value);
    if (isNaN(num)) return !0;
    if (null !== opts.min && num < opts.min && "string" != typeof opts.min) return !0;
    if (null !== opts.max && num > opts.max && "string" != typeof opts.max) return !0;
  }
  return !1;
}

function calc$1(next) {
  return function(token, getNextToken, opts) {
    return null === token ? 0 : token.type === types$1z.Function && eqStrAny$1(token.value, calcFunctionNames$1) ? function(token, getNextToken) {
      let balanceCloseType = 0, balanceStash = [], length = 0;
      scan: do {
        switch (token.type) {
         case types$1z.RightCurlyBracket:
         case types$1z.RightParenthesis:
         case types$1z.RightSquareBracket:
          if (token.type !== balanceCloseType) break scan;
          if (balanceCloseType = balanceStash.pop(), 0 === balanceStash.length) {
            length++;
            break scan;
          }
          break;

         case types$1z.Function:
         case types$1z.LeftParenthesis:
         case types$1z.LeftSquareBracket:
         case types$1z.LeftCurlyBracket:
          balanceStash.push(balanceCloseType), balanceCloseType = balancePair$2.get(token.type);
        }
        length++;
      } while (token = getNextToken(length));
      return length;
    }(token, getNextToken) : next(token, getNextToken, opts);
  };
}

function tokenType$1(expectedTokenType) {
  return function(token) {
    return null === token || token.type !== expectedTokenType ? 0 : 1;
  };
}

function dimension$1(type) {
  return type && (type = new Set(type)), function(token, getNextToken, opts) {
    if (null === token || token.type !== types$1z.Dimension) return 0;
    const numberEnd = utils$o.consumeNumber(token.value, 0);
    if (null !== type) {
      const reverseSolidusOffset = token.value.indexOf("\\", numberEnd), unit = -1 !== reverseSolidusOffset && isPostfixIeHack$1(token.value, reverseSolidusOffset) ? token.value.substring(numberEnd, reverseSolidusOffset) : token.value.substr(numberEnd);
      if (!1 === type.has(unit.toLowerCase())) return 0;
    }
    return outOfRange$1(opts, token.value, numberEnd) ? 0 : 1;
  };
}

function zero$1(next) {
  return "function" != typeof next && (next = function() {
    return 0;
  }), function(token, getNextToken, opts) {
    return null !== token && token.type === types$1z.Number && 0 === Number(token.value) ? 1 : next(token, getNextToken, opts);
  };
}

const tokenTypes = {
  "ident-token": tokenType$1(types$1z.Ident),
  "function-token": tokenType$1(types$1z.Function),
  "at-keyword-token": tokenType$1(types$1z.AtKeyword),
  "hash-token": tokenType$1(types$1z.Hash),
  "string-token": tokenType$1(types$1z.String),
  "bad-string-token": tokenType$1(types$1z.BadString),
  "url-token": tokenType$1(types$1z.Url),
  "bad-url-token": tokenType$1(types$1z.BadUrl),
  "delim-token": tokenType$1(types$1z.Delim),
  "number-token": tokenType$1(types$1z.Number),
  "percentage-token": tokenType$1(types$1z.Percentage),
  "dimension-token": tokenType$1(types$1z.Dimension),
  "whitespace-token": tokenType$1(types$1z.WhiteSpace),
  "CDO-token": tokenType$1(types$1z.CDO),
  "CDC-token": tokenType$1(types$1z.CDC),
  "colon-token": tokenType$1(types$1z.Colon),
  "semicolon-token": tokenType$1(types$1z.Semicolon),
  "comma-token": tokenType$1(types$1z.Comma),
  "[-token": tokenType$1(types$1z.LeftSquareBracket),
  "]-token": tokenType$1(types$1z.RightSquareBracket),
  "(-token": tokenType$1(types$1z.LeftParenthesis),
  ")-token": tokenType$1(types$1z.RightParenthesis),
  "{-token": tokenType$1(types$1z.LeftCurlyBracket),
  "}-token": tokenType$1(types$1z.RightCurlyBracket)
}, productionTypes = {
  "string": tokenType$1(types$1z.String),
  "ident": tokenType$1(types$1z.Ident),
  "percentage": calc$1((function(token, getNextToken, opts) {
    return null === token || token.type !== types$1z.Percentage || outOfRange$1(opts, token.value, token.value.length - 1) ? 0 : 1;
  })),
  "zero": zero$1(),
  "number": calc$1((function(token, getNextToken, opts) {
    if (null === token) return 0;
    const numberEnd = utils$o.consumeNumber(token.value, 0);
    return numberEnd === token.value.length || isPostfixIeHack$1(token.value, numberEnd) ? outOfRange$1(opts, token.value, numberEnd) ? 0 : 1 : 0;
  })),
  "integer": calc$1((function(token, getNextToken, opts) {
    if (null === token || token.type !== types$1z.Number) return 0;
    let i = 0x002B === charCodeAt$1(token.value, 0) || 0x002D === charCodeAt$1(token.value, 0) ? 1 : 0;
    for (;i < token.value.length; i++) if (!charCodeDefinitions$j.isDigit(charCodeAt$1(token.value, i))) return 0;
    return outOfRange$1(opts, token.value, i) ? 0 : 1;
  })),
  "custom-ident": function(token) {
    if (null === token || token.type !== types$1z.Ident) return 0;
    const name = token.value.toLowerCase();
    return eqStrAny$1(name, genericConst$4.cssWideKeywords) || eqStr$1(name, "default") ? 0 : 1;
  },
  "custom-property-name": function(token) {
    return null === token || token.type !== types$1z.Ident || 0x002D !== charCodeAt$1(token.value, 0) || 0x002D !== charCodeAt$1(token.value, 1) ? 0 : 1;
  },
  "hex-color": function(token) {
    if (null === token || token.type !== types$1z.Hash) return 0;
    const length = token.value.length;
    if (4 !== length && 5 !== length && 7 !== length && 9 !== length) return 0;
    for (let i = 1; i < length; i++) if (!charCodeDefinitions$j.isHexDigit(charCodeAt$1(token.value, i))) return 0;
    return 1;
  },
  "id-selector": function(token) {
    return null === token || token.type !== types$1z.Hash ? 0 : charCodeDefinitions$j.isIdentifierStart(charCodeAt$1(token.value, 1), charCodeAt$1(token.value, 2), charCodeAt$1(token.value, 3)) ? 1 : 0;
  },
  "an-plus-b": genericAnPlusB$2,
  "urange": genericUrange$2,
  "declaration-value": function(token, getNextToken) {
    if (!token) return 0;
    let balanceCloseType = 0, balanceStash = [], length = 0;
    scan: do {
      switch (token.type) {
       case types$1z.BadString:
       case types$1z.BadUrl:
        break scan;

       case types$1z.RightCurlyBracket:
       case types$1z.RightParenthesis:
       case types$1z.RightSquareBracket:
        if (token.type !== balanceCloseType) break scan;
        balanceCloseType = balanceStash.pop();
        break;

       case types$1z.Semicolon:
        if (0 === balanceCloseType) break scan;
        break;

       case types$1z.Delim:
        if (0 === balanceCloseType && "!" === token.value) break scan;
        break;

       case types$1z.Function:
       case types$1z.LeftParenthesis:
       case types$1z.LeftSquareBracket:
       case types$1z.LeftCurlyBracket:
        balanceStash.push(balanceCloseType), balanceCloseType = balancePair$2.get(token.type);
      }
      length++;
    } while (token = getNextToken(length));
    return length;
  },
  "any-value": function(token, getNextToken) {
    if (!token) return 0;
    let balanceCloseType = 0, balanceStash = [], length = 0;
    scan: do {
      switch (token.type) {
       case types$1z.BadString:
       case types$1z.BadUrl:
        break scan;

       case types$1z.RightCurlyBracket:
       case types$1z.RightParenthesis:
       case types$1z.RightSquareBracket:
        if (token.type !== balanceCloseType) break scan;
        balanceCloseType = balanceStash.pop();
        break;

       case types$1z.Function:
       case types$1z.LeftParenthesis:
       case types$1z.LeftSquareBracket:
       case types$1z.LeftCurlyBracket:
        balanceStash.push(balanceCloseType), balanceCloseType = balancePair$2.get(token.type);
      }
      length++;
    } while (token = getNextToken(length));
    return length;
  }
};

function createDemensionTypes(units) {
  const {angle, decibel, frequency, flex, length, resolution, semitones, time} = units || {};
  return {
    "dimension": calc$1(dimension$1(null)),
    "angle": calc$1(dimension$1(angle)),
    "decibel": calc$1(dimension$1(decibel)),
    "frequency": calc$1(dimension$1(frequency)),
    "flex": calc$1(dimension$1(flex)),
    "length": calc$1(zero$1(dimension$1(length))),
    "resolution": calc$1(dimension$1(resolution)),
    "semitones": calc$1(dimension$1(semitones)),
    "time": calc$1(dimension$1(time))
  };
}

generic$3.createDemensionTypes = createDemensionTypes, generic$3.createGenericTypes = function(units) {
  return {
    ...tokenTypes,
    ...productionTypes,
    ...createDemensionTypes(units)
  };
}, generic$3.productionTypes = productionTypes, generic$3.tokenTypes = tokenTypes;

var units$1 = {};

units$1.angle = [ "deg", "grad", "rad", "turn" ], units$1.decibel = [ "db" ], units$1.flex = [ "fr" ], 
units$1.frequency = [ "hz", "khz" ], units$1.length = [ "cm", "mm", "q", "in", "pt", "pc", "px", "em", "rem", "ex", "rex", "cap", "rcap", "ch", "rch", "ic", "ric", "lh", "rlh", "vw", "svw", "lvw", "dvw", "vh", "svh", "lvh", "dvh", "vi", "svi", "lvi", "dvi", "vb", "svb", "lvb", "dvb", "vmin", "svmin", "lvmin", "dvmin", "vmax", "svmax", "lvmax", "dvmax", "cqw", "cqh", "cqi", "cqb", "cqmin", "cqmax" ], 
units$1.resolution = [ "dpi", "dpcm", "dppx", "x" ], units$1.semitones = [ "st" ], 
units$1.time = [ "s", "ms" ];

const index$h = tokenizer$5, astToTokens$1 = {
  decorator(handlers) {
    const tokens = [];
    let curNode = null;
    return {
      ...handlers,
      node(node) {
        const tmp = curNode;
        curNode = node, handlers.node.call(this, node), curNode = tmp;
      },
      emit(value, type, auto) {
        tokens.push({
          type,
          value,
          node: auto ? null : curNode
        });
      },
      result: () => tokens
    };
  }
};

var prepareTokens_1$1 = function(value, syntax) {
  return "string" == typeof value ? function(str) {
    const tokens = [];
    return index$h.tokenize(str, ((type, start, end) => tokens.push({
      type,
      value: str.slice(start, end),
      node: null
    }))), tokens;
  }(value) : syntax.generate(value, astToTokens$1);
}, matchGraph$5 = {}, parse$1u = {}, tokenizer$4 = {}, _SyntaxError$2 = {};

const createCustomError$5 = createCustomError$9;

_SyntaxError$2.SyntaxError = function(message, input, offset) {
  return Object.assign(createCustomError$5.createCustomError("SyntaxError", message), {
    input,
    offset,
    rawMessage: message,
    message: message + "\n  " + input + "\n--" + new Array((offset || input.length) + 1).join("-") + "^"
  });
};

const SyntaxError$7 = _SyntaxError$2;

tokenizer$4.Tokenizer = class {
  constructor(str) {
    this.str = str, this.pos = 0;
  }
  charCodeAt(pos) {
    return pos < this.str.length ? this.str.charCodeAt(pos) : 0;
  }
  charCode() {
    return this.charCodeAt(this.pos);
  }
  nextCharCode() {
    return this.charCodeAt(this.pos + 1);
  }
  nextNonWsCode(pos) {
    return this.charCodeAt(this.findWsEnd(pos));
  }
  findWsEnd(pos) {
    for (;pos < this.str.length; pos++) {
      const code = this.str.charCodeAt(pos);
      if (13 !== code && 10 !== code && 12 !== code && 32 !== code && 9 !== code) break;
    }
    return pos;
  }
  substringToPos(end) {
    return this.str.substring(this.pos, this.pos = end);
  }
  eat(code) {
    this.charCode() !== code && this.error("Expect `" + String.fromCharCode(code) + "`"), 
    this.pos++;
  }
  peek() {
    return this.pos < this.str.length ? this.str.charAt(this.pos++) : "";
  }
  error(message) {
    throw new SyntaxError$7.SyntaxError(message, this.str, this.pos);
  }
};

const tokenizer$3 = tokenizer$4, TAB$2 = 9, N$6 = 10, F$3 = 12, R$3 = 13, SPACE$6 = 32, EXCLAMATIONMARK$6 = 33, NUMBERSIGN$8 = 35, AMPERSAND$7 = 38, APOSTROPHE$5 = 39, LEFTPARENTHESIS$5 = 40, RIGHTPARENTHESIS$5 = 41, ASTERISK$d = 42, PLUSSIGN$g = 43, COMMA$1 = 44, HYPERMINUS$1 = 45, LESSTHANSIGN$1 = 60, GREATERTHANSIGN$5 = 62, QUESTIONMARK$4 = 63, COMMERCIALAT$1 = 64, LEFTSQUAREBRACKET$1 = 91, RIGHTSQUAREBRACKET$1 = 93, LEFTCURLYBRACKET$2 = 123, VERTICALLINE$7 = 124, RIGHTCURLYBRACKET$1 = 125, INFINITY$1 = 8734, NAME_CHAR$1 = new Uint8Array(128).map(((_, idx) => /[a-zA-Z0-9\-]/.test(String.fromCharCode(idx)) ? 1 : 0)), COMBINATOR_PRECEDENCE$1 = {
  " ": 1,
  "&&": 2,
  "||": 3,
  "|": 4
};

function scanSpaces$1(tokenizer) {
  return tokenizer.substringToPos(tokenizer.findWsEnd(tokenizer.pos));
}

function scanWord$1(tokenizer) {
  let end = tokenizer.pos;
  for (;end < tokenizer.str.length; end++) {
    const code = tokenizer.str.charCodeAt(end);
    if (code >= 128 || 0 === NAME_CHAR$1[code]) break;
  }
  return tokenizer.pos === end && tokenizer.error("Expect a keyword"), tokenizer.substringToPos(end);
}

function scanNumber$1(tokenizer) {
  let end = tokenizer.pos;
  for (;end < tokenizer.str.length; end++) {
    const code = tokenizer.str.charCodeAt(end);
    if (code < 48 || code > 57) break;
  }
  return tokenizer.pos === end && tokenizer.error("Expect a number"), tokenizer.substringToPos(end);
}

function scanString$1(tokenizer) {
  const end = tokenizer.str.indexOf("'", tokenizer.pos + 1);
  return -1 === end && (tokenizer.pos = tokenizer.str.length, tokenizer.error("Expect an apostrophe")), 
  tokenizer.substringToPos(end + 1);
}

function readMultiplierRange$1(tokenizer) {
  let min = null, max = null;
  return tokenizer.eat(LEFTCURLYBRACKET$2), min = scanNumber$1(tokenizer), tokenizer.charCode() === COMMA$1 ? (tokenizer.pos++, 
  tokenizer.charCode() !== RIGHTCURLYBRACKET$1 && (max = scanNumber$1(tokenizer))) : max = min, 
  tokenizer.eat(RIGHTCURLYBRACKET$1), {
    min: Number(min),
    max: max ? Number(max) : 0
  };
}

function maybeMultiplied$1(tokenizer, node) {
  const multiplier = function(tokenizer) {
    let range = null, comma = !1;
    switch (tokenizer.charCode()) {
     case ASTERISK$d:
      tokenizer.pos++, range = {
        min: 0,
        max: 0
      };
      break;

     case PLUSSIGN$g:
      tokenizer.pos++, range = {
        min: 1,
        max: 0
      };
      break;

     case QUESTIONMARK$4:
      tokenizer.pos++, range = {
        min: 0,
        max: 1
      };
      break;

     case NUMBERSIGN$8:
      tokenizer.pos++, comma = !0, tokenizer.charCode() === LEFTCURLYBRACKET$2 ? range = readMultiplierRange$1(tokenizer) : tokenizer.charCode() === QUESTIONMARK$4 ? (tokenizer.pos++, 
      range = {
        min: 0,
        max: 0
      }) : range = {
        min: 1,
        max: 0
      };
      break;

     case LEFTCURLYBRACKET$2:
      range = readMultiplierRange$1(tokenizer);
      break;

     default:
      return null;
    }
    return {
      type: "Multiplier",
      comma,
      min: range.min,
      max: range.max,
      term: null
    };
  }(tokenizer);
  return null !== multiplier ? (multiplier.term = node, tokenizer.charCode() === NUMBERSIGN$8 && tokenizer.charCodeAt(tokenizer.pos - 1) === PLUSSIGN$g ? maybeMultiplied$1(tokenizer, multiplier) : multiplier) : node;
}

function maybeToken$1(tokenizer) {
  const ch = tokenizer.peek();
  return "" === ch ? null : {
    type: "Token",
    value: ch
  };
}

function readType$1(tokenizer) {
  let name, opts = null;
  return tokenizer.eat(LESSTHANSIGN$1), name = scanWord$1(tokenizer), tokenizer.charCode() === LEFTPARENTHESIS$5 && tokenizer.nextCharCode() === RIGHTPARENTHESIS$5 && (tokenizer.pos += 2, 
  name += "()"), tokenizer.charCodeAt(tokenizer.findWsEnd(tokenizer.pos)) === LEFTSQUAREBRACKET$1 && (scanSpaces$1(tokenizer), 
  opts = function(tokenizer) {
    let min = null, max = null, sign = 1;
    return tokenizer.eat(LEFTSQUAREBRACKET$1), tokenizer.charCode() === HYPERMINUS$1 && (tokenizer.peek(), 
    sign = -1), -1 == sign && tokenizer.charCode() === INFINITY$1 ? tokenizer.peek() : (min = sign * Number(scanNumber$1(tokenizer)), 
    0 !== NAME_CHAR$1[tokenizer.charCode()] && (min += scanWord$1(tokenizer))), scanSpaces$1(tokenizer), 
    tokenizer.eat(COMMA$1), scanSpaces$1(tokenizer), tokenizer.charCode() === INFINITY$1 ? tokenizer.peek() : (sign = 1, 
    tokenizer.charCode() === HYPERMINUS$1 && (tokenizer.peek(), sign = -1), max = sign * Number(scanNumber$1(tokenizer)), 
    0 !== NAME_CHAR$1[tokenizer.charCode()] && (max += scanWord$1(tokenizer))), tokenizer.eat(RIGHTSQUAREBRACKET$1), 
    {
      type: "Range",
      min,
      max
    };
  }(tokenizer)), tokenizer.eat(GREATERTHANSIGN$5), maybeMultiplied$1(tokenizer, {
    type: "Type",
    name,
    opts
  });
}

function regroupTerms$1(terms, combinators) {
  function createGroup(terms, combinator) {
    return {
      type: "Group",
      terms,
      combinator,
      disallowEmpty: !1,
      explicit: !1
    };
  }
  let combinator;
  for (combinators = Object.keys(combinators).sort(((a, b) => COMBINATOR_PRECEDENCE$1[a] - COMBINATOR_PRECEDENCE$1[b])); combinators.length > 0; ) {
    combinator = combinators.shift();
    let i = 0, subgroupStart = 0;
    for (;i < terms.length; i++) {
      const term = terms[i];
      "Combinator" === term.type && (term.value === combinator ? (-1 === subgroupStart && (subgroupStart = i - 1), 
      terms.splice(i, 1), i--) : (-1 !== subgroupStart && i - subgroupStart > 1 && (terms.splice(subgroupStart, i - subgroupStart, createGroup(terms.slice(subgroupStart, i), combinator)), 
      i = subgroupStart + 1), subgroupStart = -1));
    }
    -1 !== subgroupStart && combinators.length && terms.splice(subgroupStart, i - subgroupStart, createGroup(terms.slice(subgroupStart, i), combinator));
  }
  return combinator;
}

function readImplicitGroup$1(tokenizer) {
  const terms = [], combinators = {};
  let token, prevToken = null, prevTokenPos = tokenizer.pos;
  for (;token = peek$1(tokenizer); ) "Spaces" !== token.type && ("Combinator" === token.type ? (null !== prevToken && "Combinator" !== prevToken.type || (tokenizer.pos = prevTokenPos, 
  tokenizer.error("Unexpected combinator")), combinators[token.value] = !0) : null !== prevToken && "Combinator" !== prevToken.type && (combinators[" "] = !0, 
  terms.push({
    type: "Combinator",
    value: " "
  })), terms.push(token), prevToken = token, prevTokenPos = tokenizer.pos);
  return null !== prevToken && "Combinator" === prevToken.type && (tokenizer.pos -= prevTokenPos, 
  tokenizer.error("Unexpected combinator")), {
    type: "Group",
    terms,
    combinator: regroupTerms$1(terms, combinators) || " ",
    disallowEmpty: !1,
    explicit: !1
  };
}

function peek$1(tokenizer) {
  let code = tokenizer.charCode();
  if (code < 128 && 1 === NAME_CHAR$1[code]) return function(tokenizer) {
    const name = scanWord$1(tokenizer);
    return tokenizer.charCode() === LEFTPARENTHESIS$5 ? (tokenizer.pos++, {
      type: "Function",
      name
    }) : maybeMultiplied$1(tokenizer, {
      type: "Keyword",
      name
    });
  }(tokenizer);
  switch (code) {
   case RIGHTSQUAREBRACKET$1:
    break;

   case LEFTSQUAREBRACKET$1:
    return maybeMultiplied$1(tokenizer, function(tokenizer) {
      let result;
      return tokenizer.eat(LEFTSQUAREBRACKET$1), result = readImplicitGroup$1(tokenizer), 
      tokenizer.eat(RIGHTSQUAREBRACKET$1), result.explicit = !0, tokenizer.charCode() === EXCLAMATIONMARK$6 && (tokenizer.pos++, 
      result.disallowEmpty = !0), result;
    }(tokenizer));

   case LESSTHANSIGN$1:
    return tokenizer.nextCharCode() === APOSTROPHE$5 ? function(tokenizer) {
      let name;
      return tokenizer.eat(LESSTHANSIGN$1), tokenizer.eat(APOSTROPHE$5), name = scanWord$1(tokenizer), 
      tokenizer.eat(APOSTROPHE$5), tokenizer.eat(GREATERTHANSIGN$5), maybeMultiplied$1(tokenizer, {
        type: "Property",
        name
      });
    }(tokenizer) : readType$1(tokenizer);

   case VERTICALLINE$7:
    return {
      type: "Combinator",
      value: tokenizer.substringToPos(tokenizer.pos + (tokenizer.nextCharCode() === VERTICALLINE$7 ? 2 : 1))
    };

   case AMPERSAND$7:
    return tokenizer.pos++, tokenizer.eat(AMPERSAND$7), {
      type: "Combinator",
      value: "&&"
    };

   case COMMA$1:
    return tokenizer.pos++, {
      type: "Comma"
    };

   case APOSTROPHE$5:
    return maybeMultiplied$1(tokenizer, {
      type: "String",
      value: scanString$1(tokenizer)
    });

   case SPACE$6:
   case TAB$2:
   case N$6:
   case R$3:
   case F$3:
    return {
      type: "Spaces",
      value: scanSpaces$1(tokenizer)
    };

   case COMMERCIALAT$1:
    return code = tokenizer.nextCharCode(), code < 128 && 1 === NAME_CHAR$1[code] ? (tokenizer.pos++, 
    {
      type: "AtKeyword",
      name: scanWord$1(tokenizer)
    }) : maybeToken$1(tokenizer);

   case ASTERISK$d:
   case PLUSSIGN$g:
   case QUESTIONMARK$4:
   case NUMBERSIGN$8:
   case EXCLAMATIONMARK$6:
    break;

   case LEFTCURLYBRACKET$2:
    if (code = tokenizer.nextCharCode(), code < 48 || code > 57) return maybeToken$1(tokenizer);
    break;

   default:
    return maybeToken$1(tokenizer);
  }
}

parse$1u.parse = function(source) {
  const tokenizer$1 = new tokenizer$3.Tokenizer(source), result = readImplicitGroup$1(tokenizer$1);
  return tokenizer$1.pos !== source.length && tokenizer$1.error("Unexpected input"), 
  1 === result.terms.length && "Group" === result.terms[0].type ? result.terms[0] : result;
};

const parse$1s = parse$1u, MATCH$1 = {
  type: "Match"
}, MISMATCH$1 = {
  type: "Mismatch"
}, DISALLOW_EMPTY$1 = {
  type: "DisallowEmpty"
}, LEFTPARENTHESIS$4 = 40, RIGHTPARENTHESIS$4 = 41;

function createCondition$1(match, thenBranch, elseBranch) {
  return thenBranch === MATCH$1 && elseBranch === MISMATCH$1 || match === MATCH$1 && thenBranch === MATCH$1 && elseBranch === MATCH$1 ? match : ("If" === match.type && match.else === MISMATCH$1 && thenBranch === MATCH$1 && (thenBranch = match.then, 
  match = match.match), {
    type: "If",
    match,
    then: thenBranch,
    else: elseBranch
  });
}

function isFunctionType$1(name) {
  return name.length > 2 && name.charCodeAt(name.length - 2) === LEFTPARENTHESIS$4 && name.charCodeAt(name.length - 1) === RIGHTPARENTHESIS$4;
}

function isEnumCapatible$1(term) {
  return "Keyword" === term.type || "AtKeyword" === term.type || "Function" === term.type || "Type" === term.type && isFunctionType$1(term.name);
}

function buildGroupMatchGraph$1(combinator, terms, atLeastOneTermMatched) {
  switch (combinator) {
   case " ":
    {
      let result = MATCH$1;
      for (let i = terms.length - 1; i >= 0; i--) {
        result = createCondition$1(terms[i], result, MISMATCH$1);
      }
      return result;
    }

   case "|":
    {
      let result = MISMATCH$1, map = null;
      for (let i = terms.length - 1; i >= 0; i--) {
        let term = terms[i];
        if (isEnumCapatible$1(term) && (null === map && i > 0 && isEnumCapatible$1(terms[i - 1]) && (map = Object.create(null), 
        result = createCondition$1({
          type: "Enum",
          map
        }, MATCH$1, result)), null !== map)) {
          const key = (isFunctionType$1(term.name) ? term.name.slice(0, -1) : term.name).toLowerCase();
          if (key in map == !1) {
            map[key] = term;
            continue;
          }
        }
        map = null, result = createCondition$1(term, MATCH$1, result);
      }
      return result;
    }

   case "&&":
    {
      if (terms.length > 5) return {
        type: "MatchOnce",
        terms,
        all: !0
      };
      let result = MISMATCH$1;
      for (let i = terms.length - 1; i >= 0; i--) {
        const term = terms[i];
        let thenClause;
        thenClause = terms.length > 1 ? buildGroupMatchGraph$1(combinator, terms.filter((function(newGroupTerm) {
          return newGroupTerm !== term;
        })), !1) : MATCH$1, result = createCondition$1(term, thenClause, result);
      }
      return result;
    }

   case "||":
    {
      if (terms.length > 5) return {
        type: "MatchOnce",
        terms,
        all: !1
      };
      let result = atLeastOneTermMatched ? MATCH$1 : MISMATCH$1;
      for (let i = terms.length - 1; i >= 0; i--) {
        const term = terms[i];
        let thenClause;
        thenClause = terms.length > 1 ? buildGroupMatchGraph$1(combinator, terms.filter((function(newGroupTerm) {
          return newGroupTerm !== term;
        })), !0) : MATCH$1, result = createCondition$1(term, thenClause, result);
      }
      return result;
    }
  }
}

function buildMatchGraphInternal$1(node) {
  if ("function" == typeof node) return {
    type: "Generic",
    fn: node
  };
  switch (node.type) {
   case "Group":
    {
      let result = buildGroupMatchGraph$1(node.combinator, node.terms.map(buildMatchGraphInternal$1), !1);
      return node.disallowEmpty && (result = createCondition$1(result, DISALLOW_EMPTY$1, MISMATCH$1)), 
      result;
    }

   case "Multiplier":
    return function(node) {
      let result = MATCH$1, matchTerm = buildMatchGraphInternal$1(node.term);
      if (0 === node.max) matchTerm = createCondition$1(matchTerm, DISALLOW_EMPTY$1, MISMATCH$1), 
      result = createCondition$1(matchTerm, null, MISMATCH$1), result.then = createCondition$1(MATCH$1, MATCH$1, result), 
      node.comma && (result.then.else = createCondition$1({
        type: "Comma",
        syntax: node
      }, result, MISMATCH$1)); else for (let i = node.min || 1; i <= node.max; i++) node.comma && result !== MATCH$1 && (result = createCondition$1({
        type: "Comma",
        syntax: node
      }, result, MISMATCH$1)), result = createCondition$1(matchTerm, createCondition$1(MATCH$1, MATCH$1, result), MISMATCH$1);
      if (0 === node.min) result = createCondition$1(MATCH$1, MATCH$1, result); else for (let i = 0; i < node.min - 1; i++) node.comma && result !== MATCH$1 && (result = createCondition$1({
        type: "Comma",
        syntax: node
      }, result, MISMATCH$1)), result = createCondition$1(matchTerm, result, MISMATCH$1);
      return result;
    }(node);

   case "Type":
   case "Property":
    return {
      type: node.type,
      name: node.name,
      syntax: node
    };

   case "Keyword":
    return {
      type: node.type,
      name: node.name.toLowerCase(),
      syntax: node
    };

   case "AtKeyword":
    return {
      type: node.type,
      name: "@" + node.name.toLowerCase(),
      syntax: node
    };

   case "Function":
    return {
      type: node.type,
      name: node.name.toLowerCase() + "(",
      syntax: node
    };

   case "String":
    return 3 === node.value.length ? {
      type: "Token",
      value: node.value.charAt(1),
      syntax: node
    } : {
      type: node.type,
      value: node.value.substr(1, node.value.length - 2).replace(/\\'/g, "'"),
      syntax: node
    };

   case "Token":
    return {
      type: node.type,
      value: node.value,
      syntax: node
    };

   case "Comma":
    return {
      type: node.type,
      syntax: node
    };

   default:
    throw new Error("Unknown node type:", node.type);
  }
}

matchGraph$5.DISALLOW_EMPTY = DISALLOW_EMPTY$1, matchGraph$5.MATCH = MATCH$1, matchGraph$5.MISMATCH = MISMATCH$1, 
matchGraph$5.buildMatchGraph = function(syntaxTree, ref) {
  return "string" == typeof syntaxTree && (syntaxTree = parse$1s.parse(syntaxTree)), 
  {
    type: "MatchGraph",
    match: buildMatchGraphInternal$1(syntaxTree),
    syntax: ref || null,
    source: syntaxTree
  };
};

var match$3 = {};

const matchGraph$4 = matchGraph$5, types$1y = types$1I, {hasOwnProperty: hasOwnProperty$c} = Object.prototype, STUB$1 = 0, TOKEN$1 = 1, OPEN_SYNTAX$1 = 2, CLOSE_SYNTAX$1 = 3, EXIT_REASON_MATCH$1 = "Match", EXIT_REASON_MISMATCH$1 = "Mismatch", EXIT_REASON_ITERATION_LIMIT$1 = "Maximum iteration number exceeded (please fill an issue on https://github.com/csstree/csstree/issues)", ITERATION_LIMIT$1 = 15000;

function reverseList$1(list) {
  let prev = null, next = null, item = list;
  for (;null !== item; ) next = item.prev, item.prev = prev, prev = item, item = next;
  return prev;
}

function areStringsEqualCaseInsensitive$1(testStr, referenceStr) {
  if (testStr.length !== referenceStr.length) return !1;
  for (let i = 0; i < testStr.length; i++) {
    const referenceCode = referenceStr.charCodeAt(i);
    let testCode = testStr.charCodeAt(i);
    if (testCode >= 0x0041 && testCode <= 0x005A && (testCode |= 32), testCode !== referenceCode) return !1;
  }
  return !0;
}

function isCommaContextStart$1(token) {
  return null === token || (token.type === types$1y.Comma || token.type === types$1y.Function || token.type === types$1y.LeftParenthesis || token.type === types$1y.LeftSquareBracket || token.type === types$1y.LeftCurlyBracket || function(token) {
    return token.type === types$1y.Delim && "?" !== token.value;
  }(token));
}

function isCommaContextEnd$1(token) {
  return null === token || (token.type === types$1y.RightParenthesis || token.type === types$1y.RightSquareBracket || token.type === types$1y.RightCurlyBracket || token.type === types$1y.Delim && "/" === token.value);
}

function internalMatch$1(tokens, state, syntaxes) {
  function moveToNextToken() {
    do {
      tokenIndex++, token = tokenIndex < tokens.length ? tokens[tokenIndex] : null;
    } while (null !== token && (token.type === types$1y.WhiteSpace || token.type === types$1y.Comment));
  }
  function getNextToken(offset) {
    const nextIndex = tokenIndex + offset;
    return nextIndex < tokens.length ? tokens[nextIndex] : null;
  }
  function stateSnapshotFromSyntax(nextState, prev) {
    return {
      nextState,
      matchStack,
      syntaxStack,
      thenStack,
      tokenIndex,
      prev
    };
  }
  function pushThenStack(nextState) {
    thenStack = {
      nextState,
      matchStack,
      syntaxStack,
      prev: thenStack
    };
  }
  function pushElseStack(nextState) {
    elseStack = stateSnapshotFromSyntax(nextState, elseStack);
  }
  function addTokenToMatch() {
    matchStack = {
      type: TOKEN$1,
      syntax: state.syntax,
      token,
      prev: matchStack
    }, moveToNextToken(), syntaxStash = null, tokenIndex > longestMatch && (longestMatch = tokenIndex);
  }
  function closeSyntax() {
    matchStack = matchStack.type === OPEN_SYNTAX$1 ? matchStack.prev : {
      type: CLOSE_SYNTAX$1,
      syntax: syntaxStack.syntax,
      token: matchStack.token,
      prev: matchStack
    }, syntaxStack = syntaxStack.prev;
  }
  let syntaxStack = null, thenStack = null, elseStack = null, syntaxStash = null, iterationCount = 0, exitReason = null, token = null, tokenIndex = -1, longestMatch = 0, matchStack = {
    type: STUB$1,
    syntax: null,
    token: null,
    prev: null
  };
  for (moveToNextToken(); null === exitReason && ++iterationCount < ITERATION_LIMIT$1; ) switch (state.type) {
   case "Match":
    if (null === thenStack) {
      if (null !== token && (tokenIndex !== tokens.length - 1 || "\\0" !== token.value && "\\9" !== token.value)) {
        state = matchGraph$4.MISMATCH;
        break;
      }
      exitReason = EXIT_REASON_MATCH$1;
      break;
    }
    if ((state = thenStack.nextState) === matchGraph$4.DISALLOW_EMPTY) {
      if (thenStack.matchStack === matchStack) {
        state = matchGraph$4.MISMATCH;
        break;
      }
      state = matchGraph$4.MATCH;
    }
    for (;thenStack.syntaxStack !== syntaxStack; ) closeSyntax();
    thenStack = thenStack.prev;
    break;

   case "Mismatch":
    if (null !== syntaxStash && !1 !== syntaxStash) (null === elseStack || tokenIndex > elseStack.tokenIndex) && (elseStack = syntaxStash, 
    syntaxStash = !1); else if (null === elseStack) {
      exitReason = EXIT_REASON_MISMATCH$1;
      break;
    }
    state = elseStack.nextState, thenStack = elseStack.thenStack, syntaxStack = elseStack.syntaxStack, 
    matchStack = elseStack.matchStack, tokenIndex = elseStack.tokenIndex, token = tokenIndex < tokens.length ? tokens[tokenIndex] : null, 
    elseStack = elseStack.prev;
    break;

   case "MatchGraph":
    state = state.match;
    break;

   case "If":
    state.else !== matchGraph$4.MISMATCH && pushElseStack(state.else), state.then !== matchGraph$4.MATCH && pushThenStack(state.then), 
    state = state.match;
    break;

   case "MatchOnce":
    state = {
      type: "MatchOnceBuffer",
      syntax: state,
      index: 0,
      mask: 0
    };
    break;

   case "MatchOnceBuffer":
    {
      const terms = state.syntax.terms;
      if (state.index === terms.length) {
        if (0 === state.mask || state.syntax.all) {
          state = matchGraph$4.MISMATCH;
          break;
        }
        state = matchGraph$4.MATCH;
        break;
      }
      if (state.mask === (1 << terms.length) - 1) {
        state = matchGraph$4.MATCH;
        break;
      }
      for (;state.index < terms.length; state.index++) {
        const matchFlag = 1 << state.index;
        if (0 == (state.mask & matchFlag)) {
          pushElseStack(state), pushThenStack({
            type: "AddMatchOnce",
            syntax: state.syntax,
            mask: state.mask | matchFlag
          }), state = terms[state.index++];
          break;
        }
      }
      break;
    }

   case "AddMatchOnce":
    state = {
      type: "MatchOnceBuffer",
      syntax: state.syntax,
      index: 0,
      mask: state.mask
    };
    break;

   case "Enum":
    if (null !== token) {
      let name = token.value.toLowerCase();
      if (-1 !== name.indexOf("\\") && (name = name.replace(/\\[09].*$/, "")), hasOwnProperty$c.call(state.map, name)) {
        state = state.map[name];
        break;
      }
    }
    state = matchGraph$4.MISMATCH;
    break;

   case "Generic":
    {
      const opts = null !== syntaxStack ? syntaxStack.opts : null, lastTokenIndex = tokenIndex + Math.floor(state.fn(token, getNextToken, opts));
      if (!isNaN(lastTokenIndex) && lastTokenIndex > tokenIndex) {
        for (;tokenIndex < lastTokenIndex; ) addTokenToMatch();
        state = matchGraph$4.MATCH;
      } else state = matchGraph$4.MISMATCH;
      break;
    }

   case "Type":
   case "Property":
    {
      const syntaxDict = "Type" === state.type ? "types" : "properties", dictSyntax = hasOwnProperty$c.call(syntaxes, syntaxDict) ? syntaxes[syntaxDict][state.name] : null;
      if (!dictSyntax || !dictSyntax.match) throw new Error("Bad syntax reference: " + ("Type" === state.type ? "<" + state.name + ">" : "<'" + state.name + "'>"));
      if (!1 !== syntaxStash && null !== token && "Type" === state.type) {
        if ("custom-ident" === state.name && token.type === types$1y.Ident || "length" === state.name && "0" === token.value) {
          null === syntaxStash && (syntaxStash = stateSnapshotFromSyntax(state, elseStack)), 
          state = matchGraph$4.MISMATCH;
          break;
        }
      }
      syntaxStack = {
        syntax: state.syntax,
        opts: state.syntax.opts || null !== syntaxStack && syntaxStack.opts || null,
        prev: syntaxStack
      }, matchStack = {
        type: OPEN_SYNTAX$1,
        syntax: state.syntax,
        token: matchStack.token,
        prev: matchStack
      }, state = dictSyntax.match;
      break;
    }

   case "Keyword":
    {
      const name = state.name;
      if (null !== token) {
        let keywordName = token.value;
        if (-1 !== keywordName.indexOf("\\") && (keywordName = keywordName.replace(/\\[09].*$/, "")), 
        areStringsEqualCaseInsensitive$1(keywordName, name)) {
          addTokenToMatch(), state = matchGraph$4.MATCH;
          break;
        }
      }
      state = matchGraph$4.MISMATCH;
      break;
    }

   case "AtKeyword":
   case "Function":
    if (null !== token && areStringsEqualCaseInsensitive$1(token.value, state.name)) {
      addTokenToMatch(), state = matchGraph$4.MATCH;
      break;
    }
    state = matchGraph$4.MISMATCH;
    break;

   case "Token":
    if (null !== token && token.value === state.value) {
      addTokenToMatch(), state = matchGraph$4.MATCH;
      break;
    }
    state = matchGraph$4.MISMATCH;
    break;

   case "Comma":
    null !== token && token.type === types$1y.Comma ? isCommaContextStart$1(matchStack.token) ? state = matchGraph$4.MISMATCH : (addTokenToMatch(), 
    state = isCommaContextEnd$1(token) ? matchGraph$4.MISMATCH : matchGraph$4.MATCH) : state = isCommaContextStart$1(matchStack.token) || isCommaContextEnd$1(token) ? matchGraph$4.MATCH : matchGraph$4.MISMATCH;
    break;

   case "String":
    let string = "", lastTokenIndex = tokenIndex;
    for (;lastTokenIndex < tokens.length && string.length < state.value.length; lastTokenIndex++) string += tokens[lastTokenIndex].value;
    if (areStringsEqualCaseInsensitive$1(string, state.value)) {
      for (;tokenIndex < lastTokenIndex; ) addTokenToMatch();
      state = matchGraph$4.MATCH;
    } else state = matchGraph$4.MISMATCH;
    break;

   default:
    throw new Error("Unknown node type: " + state.type);
  }
  switch (exitReason) {
   case null:
    console.warn("[csstree-match] BREAK after " + ITERATION_LIMIT$1 + " iterations"), 
    exitReason = EXIT_REASON_ITERATION_LIMIT$1, matchStack = null;
    break;

   case EXIT_REASON_MATCH$1:
    for (;null !== syntaxStack; ) closeSyntax();
    break;

   default:
    matchStack = null;
  }
  return {
    tokens,
    reason: exitReason,
    iterations: iterationCount,
    match: matchStack,
    longestMatch
  };
}

match$3.matchAsList = function(tokens, matchGraph, syntaxes) {
  const matchResult = internalMatch$1(tokens, matchGraph, syntaxes || {});
  if (null !== matchResult.match) {
    let item = reverseList$1(matchResult.match).prev;
    for (matchResult.match = []; null !== item; ) {
      switch (item.type) {
       case OPEN_SYNTAX$1:
       case CLOSE_SYNTAX$1:
        matchResult.match.push({
          type: item.type,
          syntax: item.syntax
        });
        break;

       default:
        matchResult.match.push({
          token: item.token.value,
          node: item.token.node
        });
      }
      item = item.prev;
    }
  }
  return matchResult;
}, match$3.matchAsTree = function(tokens, matchGraph, syntaxes) {
  const matchResult = internalMatch$1(tokens, matchGraph, syntaxes || {});
  if (null === matchResult.match) return matchResult;
  let item = matchResult.match, host = matchResult.match = {
    syntax: matchGraph.syntax || null,
    match: []
  };
  const hostStack = [ host ];
  for (item = reverseList$1(item).prev; null !== item; ) {
    switch (item.type) {
     case OPEN_SYNTAX$1:
      host.match.push(host = {
        syntax: item.syntax,
        match: []
      }), hostStack.push(host);
      break;

     case CLOSE_SYNTAX$1:
      hostStack.pop(), host = hostStack[hostStack.length - 1];
      break;

     default:
      host.match.push({
        syntax: item.syntax || null,
        token: item.token.value,
        node: item.token.node
      });
    }
    item = item.prev;
  }
  return matchResult;
};

var trace$3 = {};

function getTrace$1(node) {
  function shouldPutToTrace(syntax) {
    return null !== syntax && ("Type" === syntax.type || "Property" === syntax.type || "Keyword" === syntax.type);
  }
  let result = null;
  return null !== this.matched && function hasMatch(matchNode) {
    if (Array.isArray(matchNode.match)) {
      for (let i = 0; i < matchNode.match.length; i++) if (hasMatch(matchNode.match[i])) return shouldPutToTrace(matchNode.syntax) && result.unshift(matchNode.syntax), 
      !0;
    } else if (matchNode.node === node) return result = shouldPutToTrace(matchNode.syntax) ? [ matchNode.syntax ] : [], 
    !0;
    return !1;
  }(this.matched), result;
}

function testNode$1(match, node, fn) {
  const trace = getTrace$1.call(match, node);
  return null !== trace && trace.some(fn);
}

trace$3.getTrace = getTrace$1, trace$3.isKeyword = function(node) {
  return testNode$1(this, node, (match => "Keyword" === match.type));
}, trace$3.isProperty = function(node, property) {
  return testNode$1(this, node, (match => "Property" === match.type && match.name === property));
}, trace$3.isType = function(node, type) {
  return testNode$1(this, node, (match => "Type" === match.type && match.name === type));
};

var search$3 = {};

const List$b = List$f;

function getFirstMatchNode$1(matchNode) {
  return "node" in matchNode ? matchNode.node : getFirstMatchNode$1(matchNode.match[0]);
}

function getLastMatchNode$1(matchNode) {
  return "node" in matchNode ? matchNode.node : getLastMatchNode$1(matchNode.match[matchNode.match.length - 1]);
}

search$3.matchFragments = function(lexer, ast, match, type, name) {
  const fragments = [];
  return null !== match.matched && function findFragments(matchNode) {
    if (null !== matchNode.syntax && matchNode.syntax.type === type && matchNode.syntax.name === name) {
      const start = getFirstMatchNode$1(matchNode), end = getLastMatchNode$1(matchNode);
      lexer.syntax.walk(ast, (function(node, item, list) {
        if (node === start) {
          const nodes = new List$b.List;
          do {
            if (nodes.appendData(item.data), item.data === end) break;
            item = item.next;
          } while (null !== item);
          fragments.push({
            parent: list,
            nodes
          });
        }
      }));
    }
    Array.isArray(matchNode.match) && matchNode.match.forEach(findFragments);
  }(match.matched), fragments;
};

var structure$1k = {};

const List$a = List$f, {hasOwnProperty: hasOwnProperty$b} = Object.prototype;

function isValidNumber$1(value) {
  return "number" == typeof value && isFinite(value) && Math.floor(value) === value && value >= 0;
}

function isValidLocation$1(loc) {
  return Boolean(loc) && isValidNumber$1(loc.offset) && isValidNumber$1(loc.line) && isValidNumber$1(loc.column);
}

function createNodeStructureChecker$1(type, fields) {
  return function(node, warn) {
    if (!node || node.constructor !== Object) return warn(node, "Type of node should be an Object");
    for (let key in node) {
      let valid = !0;
      if (!1 !== hasOwnProperty$b.call(node, key)) {
        if ("type" === key) node.type !== type && warn(node, "Wrong node type `" + node.type + "`, expected `" + type + "`"); else if ("loc" === key) {
          if (null === node.loc) continue;
          if (node.loc && node.loc.constructor === Object) if ("string" != typeof node.loc.source) key += ".source"; else if (isValidLocation$1(node.loc.start)) {
            if (isValidLocation$1(node.loc.end)) continue;
            key += ".end";
          } else key += ".start";
          valid = !1;
        } else if (fields.hasOwnProperty(key)) {
          valid = !1;
          for (let i = 0; !valid && i < fields[key].length; i++) {
            const fieldType = fields[key][i];
            switch (fieldType) {
             case String:
              valid = "string" == typeof node[key];
              break;

             case Boolean:
              valid = "boolean" == typeof node[key];
              break;

             case null:
              valid = null === node[key];
              break;

             default:
              "string" == typeof fieldType ? valid = node[key] && node[key].type === fieldType : Array.isArray(fieldType) && (valid = node[key] instanceof List$a.List);
            }
          }
        } else warn(node, "Unknown field `" + key + "` for " + type + " node type");
        valid || warn(node, "Bad value for `" + type + "." + key + "`");
      }
    }
    for (const key in fields) hasOwnProperty$b.call(fields, key) && !1 === hasOwnProperty$b.call(node, key) && warn(node, "Field `" + type + "." + key + "` is missed");
  };
}

function processStructure$1(name, nodeType) {
  const structure = nodeType.structure, fields = {
    type: String,
    loc: !0
  }, docs = {
    type: '"' + name + '"'
  };
  for (const key in structure) {
    if (!1 === hasOwnProperty$b.call(structure, key)) continue;
    const docsTypes = [], fieldTypes = fields[key] = Array.isArray(structure[key]) ? structure[key].slice() : [ structure[key] ];
    for (let i = 0; i < fieldTypes.length; i++) {
      const fieldType = fieldTypes[i];
      if (fieldType === String || fieldType === Boolean) docsTypes.push(fieldType.name); else if (null === fieldType) docsTypes.push("null"); else if ("string" == typeof fieldType) docsTypes.push("<" + fieldType + ">"); else {
        if (!Array.isArray(fieldType)) throw new Error("Wrong value `" + fieldType + "` in `" + name + "." + key + "` structure definition");
        docsTypes.push("List");
      }
    }
    docs[key] = docsTypes.join(" | ");
  }
  return {
    docs,
    check: createNodeStructureChecker$1(name, fields)
  };
}

structure$1k.getStructureFromConfig = function(config) {
  const structure = {};
  if (config.node) for (const name in config.node) if (hasOwnProperty$b.call(config.node, name)) {
    const nodeType = config.node[name];
    if (!nodeType.structure) throw new Error("Missed `structure` field in `" + name + "` node type definition");
    structure[name] = processStructure$1(name, nodeType);
  }
  return structure;
};

var walk$a = {};

const noop$3 = function() {};

function ensureFunction$2(value) {
  return "function" == typeof value ? value : noop$3;
}

walk$a.walk = function(node, options, context) {
  let enter = noop$3, leave = noop$3;
  if ("function" == typeof options ? enter = options : options && (enter = ensureFunction$2(options.enter), 
  leave = ensureFunction$2(options.leave)), enter === noop$3 && leave === noop$3) throw new Error("Neither `enter` nor `leave` walker handler is set or both aren't a function");
  !function walk(node) {
    switch (enter.call(context, node), node.type) {
     case "Group":
      node.terms.forEach(walk);
      break;

     case "Multiplier":
      walk(node.term);
      break;

     case "Type":
     case "Property":
     case "Keyword":
     case "AtKeyword":
     case "Function":
     case "String":
     case "Token":
     case "Comma":
      break;

     default:
      throw new Error("Unknown type: " + node.type);
    }
    leave.call(context, node);
  }(node);
};

const error$3 = error$4, names$b = names$c, genericConst$3 = genericConst$5, generic$2 = generic$3, units = units$1, prepareTokens$2 = prepareTokens_1$1, matchGraph$3 = matchGraph$5, match$2 = match$3, trace$2 = trace$3, search$2 = search$3, structure$1j = structure$1k, parse$1r = parse$1u, generate$1r = generate$1u, walk$8 = walk$a, cssWideKeywordsSyntax$1 = matchGraph$3.buildMatchGraph(genericConst$3.cssWideKeywords.join(" | "));

function dumpMapSyntax$1(map, compact, syntaxAsAst) {
  const result = {};
  for (const name in map) map[name].syntax && (result[name] = syntaxAsAst ? map[name].syntax : generate$1r.generate(map[name].syntax, {
    compact
  }));
  return result;
}

function dumpAtruleMapSyntax$1(map, compact, syntaxAsAst) {
  const result = {};
  for (const [name, atrule] of Object.entries(map)) result[name] = {
    prelude: atrule.prelude && (syntaxAsAst ? atrule.prelude.syntax : generate$1r.generate(atrule.prelude.syntax, {
      compact
    })),
    descriptors: atrule.descriptors && dumpMapSyntax$1(atrule.descriptors, compact, syntaxAsAst)
  };
  return result;
}

function buildMatchResult$1(matched, error, iterations) {
  return {
    matched,
    iterations,
    error,
    ...trace$2
  };
}

function matchSyntax$1(lexer, syntax, value, useCssWideKeywords) {
  const tokens = prepareTokens$2(value, lexer.syntax);
  let result;
  return function(tokens) {
    for (let i = 0; i < tokens.length; i++) if ("var(" === tokens[i].value.toLowerCase()) return !0;
    return !1;
  }(tokens) ? buildMatchResult$1(null, new Error("Matching for a tree with var() is not supported")) : (useCssWideKeywords && (result = match$2.matchAsTree(tokens, lexer.cssWideKeywordsSyntax, lexer)), 
  useCssWideKeywords && result.match || (result = match$2.matchAsTree(tokens, syntax.match, lexer), 
  result.match) ? buildMatchResult$1(result.match, null, result.iterations) : buildMatchResult$1(null, new error$3.SyntaxMatchError(result.reason, syntax.syntax, value, result), result.iterations));
}

function appendOrSet(a, b) {
  return "string" == typeof b && /^\s*\|/.test(b) ? "string" == typeof a ? a + b : b.replace(/^\s*\|\s*/, "") : b || null;
}

function sliceProps(obj, props) {
  const result = Object.create(null);
  for (const [key, value] of Object.entries(obj)) if (value) {
    result[key] = {};
    for (const prop of Object.keys(value)) props.includes(prop) && (result[key][prop] = value[prop]);
  }
  return result;
}

Lexer$7.Lexer = class {
  constructor(config, syntax, structure$1) {
    if (this.cssWideKeywordsSyntax = cssWideKeywordsSyntax$1, this.syntax = syntax, 
    this.generic = !1, this.units = {
      ...units
    }, this.atrules = Object.create(null), this.properties = Object.create(null), this.types = Object.create(null), 
    this.structure = structure$1 || structure$1j.getStructureFromConfig(config), config) {
      if (config.units) for (const group of Object.keys(units)) Array.isArray(config.units[group]) && (this.units[group] = config.units[group]);
      if (config.types) for (const name in config.types) this.addType_(name, config.types[name]);
      if (config.generic) {
        this.generic = !0;
        for (const [name, value] of Object.entries(generic$2.createGenericTypes(this.units))) this.addType_(name, value);
      }
      if (config.atrules) for (const name in config.atrules) this.addAtrule_(name, config.atrules[name]);
      if (config.properties) for (const name in config.properties) this.addProperty_(name, config.properties[name]);
    }
  }
  checkStructure(ast) {
    function collectWarning(node, message) {
      warns.push({
        node,
        message
      });
    }
    const structure = this.structure, warns = [];
    return this.syntax.walk(ast, (function(node) {
      structure.hasOwnProperty(node.type) ? structure[node.type].check(node, collectWarning) : collectWarning(node, "Unknown node type `" + node.type + "`");
    })), !!warns.length && warns;
  }
  createDescriptor(syntax, type, name, parent = null) {
    const ref = {
      type,
      name
    }, descriptor = {
      type,
      name,
      parent,
      serializable: "string" == typeof syntax || syntax && "string" == typeof syntax.type,
      syntax: null,
      match: null
    };
    return "function" == typeof syntax ? descriptor.match = matchGraph$3.buildMatchGraph(syntax, ref) : ("string" == typeof syntax ? Object.defineProperty(descriptor, "syntax", {
      get: () => (Object.defineProperty(descriptor, "syntax", {
        value: parse$1r.parse(syntax)
      }), descriptor.syntax)
    }) : descriptor.syntax = syntax, Object.defineProperty(descriptor, "match", {
      get: () => (Object.defineProperty(descriptor, "match", {
        value: matchGraph$3.buildMatchGraph(descriptor.syntax, ref)
      }), descriptor.match)
    })), descriptor;
  }
  addAtrule_(name, syntax) {
    syntax && (this.atrules[name] = {
      type: "Atrule",
      name,
      prelude: syntax.prelude ? this.createDescriptor(syntax.prelude, "AtrulePrelude", name) : null,
      descriptors: syntax.descriptors ? Object.keys(syntax.descriptors).reduce(((map, descName) => (map[descName] = this.createDescriptor(syntax.descriptors[descName], "AtruleDescriptor", descName, name), 
      map)), Object.create(null)) : null
    });
  }
  addProperty_(name, syntax) {
    syntax && (this.properties[name] = this.createDescriptor(syntax, "Property", name));
  }
  addType_(name, syntax) {
    syntax && (this.types[name] = this.createDescriptor(syntax, "Type", name));
  }
  checkAtruleName(atruleName) {
    if (!this.getAtrule(atruleName)) return new error$3.SyntaxReferenceError("Unknown at-rule", "@" + atruleName);
  }
  checkAtrulePrelude(atruleName, prelude) {
    const error = this.checkAtruleName(atruleName);
    if (error) return error;
    const atrule = this.getAtrule(atruleName);
    return !atrule.prelude && prelude ? new SyntaxError("At-rule `@" + atruleName + "` should not contain a prelude") : !atrule.prelude || prelude || matchSyntax$1(this, atrule.prelude, "", !1).matched ? void 0 : new SyntaxError("At-rule `@" + atruleName + "` should contain a prelude");
  }
  checkAtruleDescriptorName(atruleName, descriptorName) {
    const error$1 = this.checkAtruleName(atruleName);
    if (error$1) return error$1;
    const atrule = this.getAtrule(atruleName), descriptor = names$b.keyword(descriptorName);
    return atrule.descriptors ? atrule.descriptors[descriptor.name] || atrule.descriptors[descriptor.basename] ? void 0 : new error$3.SyntaxReferenceError("Unknown at-rule descriptor", descriptorName) : new SyntaxError("At-rule `@" + atruleName + "` has no known descriptors");
  }
  checkPropertyName(propertyName) {
    if (!this.getProperty(propertyName)) return new error$3.SyntaxReferenceError("Unknown property", propertyName);
  }
  matchAtrulePrelude(atruleName, prelude) {
    const error = this.checkAtrulePrelude(atruleName, prelude);
    if (error) return buildMatchResult$1(null, error);
    const atrule = this.getAtrule(atruleName);
    return atrule.prelude ? matchSyntax$1(this, atrule.prelude, prelude || "", !1) : buildMatchResult$1(null, null);
  }
  matchAtruleDescriptor(atruleName, descriptorName, value) {
    const error = this.checkAtruleDescriptorName(atruleName, descriptorName);
    if (error) return buildMatchResult$1(null, error);
    const atrule = this.getAtrule(atruleName), descriptor = names$b.keyword(descriptorName);
    return matchSyntax$1(this, atrule.descriptors[descriptor.name] || atrule.descriptors[descriptor.basename], value, !1);
  }
  matchDeclaration(node) {
    return "Declaration" !== node.type ? buildMatchResult$1(null, new Error("Not a Declaration node")) : this.matchProperty(node.property, node.value);
  }
  matchProperty(propertyName, value) {
    if (names$b.property(propertyName).custom) return buildMatchResult$1(null, new Error("Lexer matching doesn't applicable for custom properties"));
    const error = this.checkPropertyName(propertyName);
    return error ? buildMatchResult$1(null, error) : matchSyntax$1(this, this.getProperty(propertyName), value, !0);
  }
  matchType(typeName, value) {
    const typeSyntax = this.getType(typeName);
    return typeSyntax ? matchSyntax$1(this, typeSyntax, value, !1) : buildMatchResult$1(null, new error$3.SyntaxReferenceError("Unknown type", typeName));
  }
  match(syntax, value) {
    return "string" == typeof syntax || syntax && syntax.type ? ("string" != typeof syntax && syntax.match || (syntax = this.createDescriptor(syntax, "Type", "anonymous")), 
    matchSyntax$1(this, syntax, value, !1)) : buildMatchResult$1(null, new error$3.SyntaxReferenceError("Bad syntax"));
  }
  findValueFragments(propertyName, value, type, name) {
    return search$2.matchFragments(this, value, this.matchProperty(propertyName, value), type, name);
  }
  findDeclarationValueFragments(declaration, type, name) {
    return search$2.matchFragments(this, declaration.value, this.matchDeclaration(declaration), type, name);
  }
  findAllFragments(ast, type, name) {
    const result = [];
    return this.syntax.walk(ast, {
      visit: "Declaration",
      enter: declaration => {
        result.push.apply(result, this.findDeclarationValueFragments(declaration, type, name));
      }
    }), result;
  }
  getAtrule(atruleName, fallbackBasename = !0) {
    const atrule = names$b.keyword(atruleName);
    return (atrule.vendor && fallbackBasename ? this.atrules[atrule.name] || this.atrules[atrule.basename] : this.atrules[atrule.name]) || null;
  }
  getAtrulePrelude(atruleName, fallbackBasename = !0) {
    const atrule = this.getAtrule(atruleName, fallbackBasename);
    return atrule && atrule.prelude || null;
  }
  getAtruleDescriptor(atruleName, name) {
    return this.atrules.hasOwnProperty(atruleName) && this.atrules.declarators && this.atrules[atruleName].declarators[name] || null;
  }
  getProperty(propertyName, fallbackBasename = !0) {
    const property = names$b.property(propertyName);
    return (property.vendor && fallbackBasename ? this.properties[property.name] || this.properties[property.basename] : this.properties[property.name]) || null;
  }
  getType(name) {
    return hasOwnProperty.call(this.types, name) ? this.types[name] : null;
  }
  validate() {
    function validate(syntax, name, broken, descriptor) {
      if (broken.has(name)) return broken.get(name);
      broken.set(name, !1), null !== descriptor.syntax && walk$8.walk(descriptor.syntax, (function(node) {
        if ("Type" !== node.type && "Property" !== node.type) return;
        const map = "Type" === node.type ? syntax.types : syntax.properties, brokenMap = "Type" === node.type ? brokenTypes : brokenProperties;
        hasOwnProperty.call(map, node.name) && !validate(syntax, node.name, brokenMap, map[node.name]) || broken.set(name, !0);
      }), this);
    }
    let brokenTypes = new Map, brokenProperties = new Map;
    for (const key in this.types) validate(this, key, brokenTypes, this.types[key]);
    for (const key in this.properties) validate(this, key, brokenProperties, this.properties[key]);
    return brokenTypes = [ ...brokenTypes.keys() ].filter((name => brokenTypes.get(name))), 
    brokenProperties = [ ...brokenProperties.keys() ].filter((name => brokenProperties.get(name))), 
    brokenTypes.length || brokenProperties.length ? {
      types: brokenTypes,
      properties: brokenProperties
    } : null;
  }
  dump(syntaxAsAst, pretty) {
    return {
      generic: this.generic,
      units: this.units,
      types: dumpMapSyntax$1(this.types, !pretty, syntaxAsAst),
      properties: dumpMapSyntax$1(this.properties, !pretty, syntaxAsAst),
      atrules: dumpAtruleMapSyntax$1(this.atrules, !pretty, syntaxAsAst)
    };
  }
  toString() {
    return JSON.stringify(this.dump());
  }
};

var mix_1$1 = function(dest, src) {
  const result = {
    ...dest
  };
  for (const [prop, value] of Object.entries(src)) switch (prop) {
   case "generic":
    result[prop] = Boolean(value);
    break;

   case "units":
    result[prop] = {
      ...dest[prop]
    };
    for (const [name, patch] of Object.entries(value)) result[prop][name] = Array.isArray(patch) ? patch : [];
    break;

   case "atrules":
    result[prop] = {
      ...dest[prop]
    };
    for (const [name, atrule] of Object.entries(value)) {
      const exists = result[prop][name] || {}, current = result[prop][name] = {
        prelude: exists.prelude || null,
        descriptors: {
          ...exists.descriptors
        }
      };
      if (atrule) {
        current.prelude = atrule.prelude ? appendOrSet(current.prelude, atrule.prelude) : current.prelude || null;
        for (const [descriptorName, descriptorValue] of Object.entries(atrule.descriptors || {})) current.descriptors[descriptorName] = descriptorValue ? appendOrSet(current.descriptors[descriptorName], descriptorValue) : null;
        Object.keys(current.descriptors).length || (current.descriptors = null);
      }
    }
    break;

   case "types":
   case "properties":
    result[prop] = {
      ...dest[prop]
    };
    for (const [name, syntax] of Object.entries(value)) result[prop][name] = appendOrSet(result[prop][name], syntax);
    break;

   case "scope":
    result[prop] = {
      ...dest[prop]
    };
    for (const [name, props] of Object.entries(value)) result[prop][name] = {
      ...result[prop][name],
      ...props
    };
    break;

   case "parseContext":
    result[prop] = {
      ...dest[prop],
      ...value
    };
    break;

   case "atrule":
   case "pseudo":
    result[prop] = {
      ...dest[prop],
      ...sliceProps(value, [ "parse" ])
    };
    break;

   case "node":
    result[prop] = {
      ...dest[prop],
      ...sliceProps(value, [ "name", "structure", "parse", "generate", "walkContext" ])
    };
  }
  return result;
};

const index$g = tokenizer$5, create$a = create$e, create$2$2 = create$d, create$3$1 = create$c, create$1$2 = create$b, Lexer$5 = Lexer$7, mix$2 = mix_1$1;

function createSyntax$2(config) {
  const parse = create$a.createParser(config), walk = create$1$2.createWalker(config), generate = create$2$2.createGenerator(config), {fromPlainObject, toPlainObject} = create$3$1.createConvertor(walk), syntax = {
    lexer: null,
    createLexer: config => new Lexer$5.Lexer(config, syntax, syntax.lexer.structure),
    tokenize: index$g.tokenize,
    parse,
    generate,
    walk,
    find: walk.find,
    findLast: walk.findLast,
    findAll: walk.findAll,
    fromPlainObject,
    toPlainObject,
    fork(extension) {
      const base = mix$2({}, config);
      return createSyntax$2("function" == typeof extension ? extension(base, Object.assign) : mix$2(base, extension));
    }
  };
  return syntax.lexer = new Lexer$5.Lexer({
    generic: !0,
    units: config.units,
    types: config.types,
    atrules: config.atrules,
    properties: config.properties,
    node: config.node
  }, syntax), syntax;
}

var create_1$1 = config => createSyntax$2(mix$2({}, config)), node$1 = {}, AnPlusB$5 = {};

const types$1x = types$1I, charCodeDefinitions$i = charCodeDefinitions$p, PLUSSIGN$f = 0x002B, HYPHENMINUS$9 = 0x002D, N$5 = 0x006E, DISALLOW_SIGN$2 = !0;

function checkInteger$2(offset, disallowSign) {
  let pos = this.tokenStart + offset;
  const code = this.charCodeAt(pos);
  for (code !== PLUSSIGN$f && code !== HYPHENMINUS$9 || (disallowSign && this.error("Number sign is not allowed"), 
  pos++); pos < this.tokenEnd; pos++) charCodeDefinitions$i.isDigit(this.charCodeAt(pos)) || this.error("Integer is expected", pos);
}

function checkTokenIsInteger$1(disallowSign) {
  return checkInteger$2.call(this, 0, disallowSign);
}

function expectCharCode$1(offset, code) {
  if (!this.cmpChar(this.tokenStart + offset, code)) {
    let msg = "";
    switch (code) {
     case N$5:
      msg = "N is expected";
      break;

     case HYPHENMINUS$9:
      msg = "HyphenMinus is expected";
    }
    this.error(msg, this.tokenStart + offset);
  }
}

function consumeB$2() {
  let offset = 0, sign = 0, type = this.tokenType;
  for (;type === types$1x.WhiteSpace || type === types$1x.Comment; ) type = this.lookupType(++offset);
  if (type !== types$1x.Number) {
    if (!this.isDelim(PLUSSIGN$f, offset) && !this.isDelim(HYPHENMINUS$9, offset)) return null;
    sign = this.isDelim(PLUSSIGN$f, offset) ? PLUSSIGN$f : HYPHENMINUS$9;
    do {
      type = this.lookupType(++offset);
    } while (type === types$1x.WhiteSpace || type === types$1x.Comment);
    type !== types$1x.Number && (this.skip(offset), checkTokenIsInteger$1.call(this, DISALLOW_SIGN$2));
  }
  return offset > 0 && this.skip(offset), 0 === sign && (type = this.charCodeAt(this.tokenStart), 
  type !== PLUSSIGN$f && type !== HYPHENMINUS$9 && this.error("Number sign is expected")), 
  checkTokenIsInteger$1.call(this, 0 !== sign), sign === HYPHENMINUS$9 ? "-" + this.consume(types$1x.Number) : this.consume(types$1x.Number);
}

const structure$1i = {
  a: [ String, null ],
  b: [ String, null ]
};

AnPlusB$5.generate = function(node) {
  if (node.a) {
    const a = ("+1" === node.a || "1" === node.a ? "n" : "-1" === node.a && "-n") || node.a + "n";
    if (node.b) {
      const b = "-" === node.b[0] || "+" === node.b[0] ? node.b : "+" + node.b;
      this.tokenize(a + b);
    } else this.tokenize(a);
  } else this.tokenize(node.b);
}, AnPlusB$5.name = "AnPlusB", AnPlusB$5.parse = function() {
  const start = this.tokenStart;
  let a = null, b = null;
  if (this.tokenType === types$1x.Number) checkTokenIsInteger$1.call(this, false), 
  b = this.consume(types$1x.Number); else if (this.tokenType === types$1x.Ident && this.cmpChar(this.tokenStart, HYPHENMINUS$9)) switch (a = "-1", 
  expectCharCode$1.call(this, 1, N$5), this.tokenEnd - this.tokenStart) {
   case 2:
    this.next(), b = consumeB$2.call(this);
    break;

   case 3:
    expectCharCode$1.call(this, 2, HYPHENMINUS$9), this.next(), this.skipSC(), checkTokenIsInteger$1.call(this, DISALLOW_SIGN$2), 
    b = "-" + this.consume(types$1x.Number);
    break;

   default:
    expectCharCode$1.call(this, 2, HYPHENMINUS$9), checkInteger$2.call(this, 3, DISALLOW_SIGN$2), 
    this.next(), b = this.substrToCursor(start + 2);
  } else if (this.tokenType === types$1x.Ident || this.isDelim(PLUSSIGN$f) && this.lookupType(1) === types$1x.Ident) {
    let sign = 0;
    switch (a = "1", this.isDelim(PLUSSIGN$f) && (sign = 1, this.next()), expectCharCode$1.call(this, 0, N$5), 
    this.tokenEnd - this.tokenStart) {
     case 1:
      this.next(), b = consumeB$2.call(this);
      break;

     case 2:
      expectCharCode$1.call(this, 1, HYPHENMINUS$9), this.next(), this.skipSC(), checkTokenIsInteger$1.call(this, DISALLOW_SIGN$2), 
      b = "-" + this.consume(types$1x.Number);
      break;

     default:
      expectCharCode$1.call(this, 1, HYPHENMINUS$9), checkInteger$2.call(this, 2, DISALLOW_SIGN$2), 
      this.next(), b = this.substrToCursor(start + sign + 1);
    }
  } else if (this.tokenType === types$1x.Dimension) {
    const code = this.charCodeAt(this.tokenStart), sign = code === PLUSSIGN$f || code === HYPHENMINUS$9;
    let i = this.tokenStart + sign;
    for (;i < this.tokenEnd && charCodeDefinitions$i.isDigit(this.charCodeAt(i)); i++) ;
    i === this.tokenStart + sign && this.error("Integer is expected", this.tokenStart + sign), 
    expectCharCode$1.call(this, i - this.tokenStart, N$5), a = this.substring(start, i), 
    i + 1 === this.tokenEnd ? (this.next(), b = consumeB$2.call(this)) : (expectCharCode$1.call(this, i - this.tokenStart + 1, HYPHENMINUS$9), 
    i + 2 === this.tokenEnd ? (this.next(), this.skipSC(), checkTokenIsInteger$1.call(this, DISALLOW_SIGN$2), 
    b = "-" + this.consume(types$1x.Number)) : (checkInteger$2.call(this, i - this.tokenStart + 2, DISALLOW_SIGN$2), 
    this.next(), b = this.substrToCursor(i + 1)));
  } else this.error();
  return null !== a && a.charCodeAt(0) === PLUSSIGN$f && (a = a.substr(1)), null !== b && b.charCodeAt(0) === PLUSSIGN$f && (b = b.substr(1)), 
  {
    type: "AnPlusB",
    loc: this.getLocation(start, this.tokenStart),
    a,
    b
  };
}, AnPlusB$5.structure = structure$1i;

var Atrule$9 = {};

const types$1w = types$1I;

function consumeRaw$b(startToken) {
  return this.Raw(startToken, this.consumeUntilLeftCurlyBracketOrSemicolon, !0);
}

function isDeclarationBlockAtrule$1() {
  for (let type, offset = 1; type = this.lookupType(offset); offset++) {
    if (type === types$1w.RightCurlyBracket) return !0;
    if (type === types$1w.LeftCurlyBracket || type === types$1w.AtKeyword) return !1;
  }
  return !1;
}

const structure$1h = {
  name: String,
  prelude: [ "AtrulePrelude", "Raw", null ],
  block: [ "Block", null ]
};

Atrule$9.generate = function(node) {
  this.token(types$1w.AtKeyword, "@" + node.name), null !== node.prelude && this.node(node.prelude), 
  node.block ? this.node(node.block) : this.token(types$1w.Semicolon, ";");
}, Atrule$9.name = "Atrule", Atrule$9.parse = function(isDeclaration = !1) {
  const start = this.tokenStart;
  let name, nameLowerCase, prelude = null, block = null;
  switch (this.eat(types$1w.AtKeyword), name = this.substrToCursor(start + 1), nameLowerCase = name.toLowerCase(), 
  this.skipSC(), !1 === this.eof && this.tokenType !== types$1w.LeftCurlyBracket && this.tokenType !== types$1w.Semicolon && (prelude = this.parseAtrulePrelude ? this.parseWithFallback(this.AtrulePrelude.bind(this, name, isDeclaration), consumeRaw$b) : consumeRaw$b.call(this, this.tokenIndex), 
  this.skipSC()), this.tokenType) {
   case types$1w.Semicolon:
    this.next();
    break;

   case types$1w.LeftCurlyBracket:
    block = hasOwnProperty.call(this.atrule, nameLowerCase) && "function" == typeof this.atrule[nameLowerCase].block ? this.atrule[nameLowerCase].block.call(this, isDeclaration) : this.Block(isDeclarationBlockAtrule$1.call(this));
  }
  return {
    type: "Atrule",
    loc: this.getLocation(start, this.tokenStart),
    name,
    prelude,
    block
  };
}, Atrule$9.structure = structure$1h, Atrule$9.walkContext = "atrule";

var AtrulePrelude$5 = {};

const types$1v = types$1I;

AtrulePrelude$5.generate = function(node) {
  this.children(node);
}, AtrulePrelude$5.name = "AtrulePrelude", AtrulePrelude$5.parse = function(name) {
  let children = null;
  return null !== name && (name = name.toLowerCase()), this.skipSC(), children = hasOwnProperty.call(this.atrule, name) && "function" == typeof this.atrule[name].prelude ? this.atrule[name].prelude.call(this) : this.readSequence(this.scope.AtrulePrelude), 
  this.skipSC(), !0 !== this.eof && this.tokenType !== types$1v.LeftCurlyBracket && this.tokenType !== types$1v.Semicolon && this.error("Semicolon or block is expected"), 
  {
    type: "AtrulePrelude",
    loc: this.getLocationFromList(children),
    children
  };
}, AtrulePrelude$5.structure = {
  children: [ [] ]
}, AtrulePrelude$5.walkContext = "atrulePrelude";

var AttributeSelector$7 = {};

const types$1u = types$1I, DOLLARSIGN$3 = 0x0024, ASTERISK$c = 0x002A, EQUALSSIGN$1 = 0x003D, CIRCUMFLEXACCENT$1 = 0x005E, VERTICALLINE$6 = 0x007C, TILDE$5 = 0x007E;

function getAttributeName$1() {
  this.eof && this.error("Unexpected end of input");
  const start = this.tokenStart;
  let expectIdent = !1;
  return this.isDelim(ASTERISK$c) ? (expectIdent = !0, this.next()) : this.isDelim(VERTICALLINE$6) || this.eat(types$1u.Ident), 
  this.isDelim(VERTICALLINE$6) ? this.charCodeAt(this.tokenStart + 1) !== EQUALSSIGN$1 ? (this.next(), 
  this.eat(types$1u.Ident)) : expectIdent && this.error("Identifier is expected", this.tokenEnd) : expectIdent && this.error("Vertical line is expected"), 
  {
    type: "Identifier",
    loc: this.getLocation(start, this.tokenStart),
    name: this.substrToCursor(start)
  };
}

function getOperator$1() {
  const start = this.tokenStart, code = this.charCodeAt(start);
  return code !== EQUALSSIGN$1 && code !== TILDE$5 && code !== CIRCUMFLEXACCENT$1 && code !== DOLLARSIGN$3 && code !== ASTERISK$c && code !== VERTICALLINE$6 && this.error("Attribute selector (=, ~=, ^=, $=, *=, |=) is expected"), 
  this.next(), code !== EQUALSSIGN$1 && (this.isDelim(EQUALSSIGN$1) || this.error("Equal sign is expected"), 
  this.next()), this.substrToCursor(start);
}

const structure$1f = {
  name: "Identifier",
  matcher: [ String, null ],
  value: [ "String", "Identifier", null ],
  flags: [ String, null ]
};

AttributeSelector$7.generate = function(node) {
  this.token(types$1u.Delim, "["), this.node(node.name), null !== node.matcher && (this.tokenize(node.matcher), 
  this.node(node.value)), null !== node.flags && this.token(types$1u.Ident, node.flags), 
  this.token(types$1u.Delim, "]");
}, AttributeSelector$7.name = "AttributeSelector", AttributeSelector$7.parse = function() {
  const start = this.tokenStart;
  let name, matcher = null, value = null, flags = null;
  return this.eat(types$1u.LeftSquareBracket), this.skipSC(), name = getAttributeName$1.call(this), 
  this.skipSC(), this.tokenType !== types$1u.RightSquareBracket && (this.tokenType !== types$1u.Ident && (matcher = getOperator$1.call(this), 
  this.skipSC(), value = this.tokenType === types$1u.String ? this.String() : this.Identifier(), 
  this.skipSC()), this.tokenType === types$1u.Ident && (flags = this.consume(types$1u.Ident), 
  this.skipSC())), this.eat(types$1u.RightSquareBracket), {
    type: "AttributeSelector",
    loc: this.getLocation(start, this.tokenStart),
    name,
    matcher,
    value,
    flags
  };
}, AttributeSelector$7.structure = structure$1f;

var Block$5 = {};

const types$1t = types$1I;

function consumeRaw$a(startToken) {
  return this.Raw(startToken, null, !0);
}

function consumeRule$1() {
  return this.parseWithFallback(this.Rule, consumeRaw$a);
}

function consumeRawDeclaration$1(startToken) {
  return this.Raw(startToken, this.consumeUntilSemicolonIncluded, !0);
}

function consumeDeclaration$1() {
  if (this.tokenType === types$1t.Semicolon) return consumeRawDeclaration$1.call(this, this.tokenIndex);
  const node = this.parseWithFallback(this.Declaration, consumeRawDeclaration$1);
  return this.tokenType === types$1t.Semicolon && this.next(), node;
}

Block$5.generate = function(node) {
  this.token(types$1t.LeftCurlyBracket, "{"), this.children(node, (prev => {
    "Declaration" === prev.type && this.token(types$1t.Semicolon, ";");
  })), this.token(types$1t.RightCurlyBracket, "}");
}, Block$5.name = "Block", Block$5.parse = function(isStyleBlock) {
  const consumer = isStyleBlock ? consumeDeclaration$1 : consumeRule$1, start = this.tokenStart;
  let children = this.createList();
  this.eat(types$1t.LeftCurlyBracket);
  scan: for (;!this.eof; ) switch (this.tokenType) {
   case types$1t.RightCurlyBracket:
    break scan;

   case types$1t.WhiteSpace:
   case types$1t.Comment:
    this.next();
    break;

   case types$1t.AtKeyword:
    children.push(this.parseWithFallback(this.Atrule.bind(this, isStyleBlock), consumeRaw$a));
    break;

   default:
    isStyleBlock && this.isDelim(38) ? children.push(consumeRule$1.call(this)) : children.push(consumer.call(this));
  }
  return this.eof || this.eat(types$1t.RightCurlyBracket), {
    type: "Block",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, Block$5.structure = {
  children: [ [ "Atrule", "Rule", "Declaration" ] ]
}, Block$5.walkContext = "block";

var Brackets$5 = {};

const types$1s = types$1I;

Brackets$5.generate = function(node) {
  this.token(types$1s.Delim, "["), this.children(node), this.token(types$1s.Delim, "]");
}, Brackets$5.name = "Brackets", Brackets$5.parse = function(readSequence, recognizer) {
  const start = this.tokenStart;
  let children = null;
  return this.eat(types$1s.LeftSquareBracket), children = readSequence.call(this, recognizer), 
  this.eof || this.eat(types$1s.RightSquareBracket), {
    type: "Brackets",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, Brackets$5.structure = {
  children: [ [] ]
};

var CDC$6 = {};

const types$1r = types$1I;

CDC$6.generate = function() {
  this.token(types$1r.CDC, "--\x3e");
}, CDC$6.name = "CDC", CDC$6.parse = function() {
  const start = this.tokenStart;
  return this.eat(types$1r.CDC), {
    type: "CDC",
    loc: this.getLocation(start, this.tokenStart)
  };
}, CDC$6.structure = [];

var CDO$6 = {};

const types$1q = types$1I;

CDO$6.generate = function() {
  this.token(types$1q.CDO, "\x3c!--");
}, CDO$6.name = "CDO", CDO$6.parse = function() {
  const start = this.tokenStart;
  return this.eat(types$1q.CDO), {
    type: "CDO",
    loc: this.getLocation(start, this.tokenStart)
  };
}, CDO$6.structure = [];

var ClassSelector$5 = {};

const types$1p = types$1I, structure$1a = {
  name: String
};

ClassSelector$5.generate = function(node) {
  this.token(types$1p.Delim, "."), this.token(types$1p.Ident, node.name);
}, ClassSelector$5.name = "ClassSelector", ClassSelector$5.parse = function() {
  return this.eatDelim(46), {
    type: "ClassSelector",
    loc: this.getLocation(this.tokenStart - 1, this.tokenEnd),
    name: this.consume(types$1p.Ident)
  };
}, ClassSelector$5.structure = structure$1a;

var Combinator$5 = {};

const types$1o = types$1I, structure$19 = {
  name: String
};

Combinator$5.generate = function(node) {
  this.tokenize(node.name);
}, Combinator$5.name = "Combinator", Combinator$5.parse = function() {
  const start = this.tokenStart;
  let name;
  switch (this.tokenType) {
   case types$1o.WhiteSpace:
    name = " ";
    break;

   case types$1o.Delim:
    switch (this.charCodeAt(this.tokenStart)) {
     case 62:
     case 43:
     case 126:
      this.next();
      break;

     case 47:
      this.next(), this.eatIdent("deep"), this.eatDelim(47);
      break;

     default:
      this.error("Combinator is expected");
    }
    name = this.substrToCursor(start);
  }
  return {
    type: "Combinator",
    loc: this.getLocation(start, this.tokenStart),
    name
  };
}, Combinator$5.structure = structure$19;

var Comment$8 = {};

const types$1n = types$1I, structure$18 = {
  value: String
};

Comment$8.generate = function(node) {
  this.token(types$1n.Comment, "/*" + node.value + "*/");
}, Comment$8.name = "Comment", Comment$8.parse = function() {
  const start = this.tokenStart;
  let end = this.tokenEnd;
  return this.eat(types$1n.Comment), end - start + 2 >= 2 && 42 === this.charCodeAt(end - 2) && 47 === this.charCodeAt(end - 1) && (end -= 2), 
  {
    type: "Comment",
    loc: this.getLocation(start, this.tokenStart),
    value: this.substring(start + 2, end)
  };
}, Comment$8.structure = structure$18;

var Declaration$7 = {};

const names$a = names$c, types$1m = types$1I, EXCLAMATIONMARK$5 = 0x0021, NUMBERSIGN$7 = 0x0023, DOLLARSIGN$2 = 0x0024, AMPERSAND$5 = 0x0026, ASTERISK$a = 0x002A, PLUSSIGN$d = 0x002B, SOLIDUS$9 = 0x002F;

function consumeValueRaw$1(startToken) {
  return this.Raw(startToken, this.consumeUntilExclamationMarkOrSemicolon, !0);
}

function consumeCustomPropertyRaw$1(startToken) {
  return this.Raw(startToken, this.consumeUntilExclamationMarkOrSemicolon, !1);
}

function consumeValue$1() {
  const startValueToken = this.tokenIndex, value = this.Value();
  return "Raw" !== value.type && !1 === this.eof && this.tokenType !== types$1m.Semicolon && !1 === this.isDelim(EXCLAMATIONMARK$5) && !1 === this.isBalanceEdge(startValueToken) && this.error(), 
  value;
}

const structure$17 = {
  important: [ Boolean, String ],
  property: String,
  value: [ "Value", "Raw" ]
};

function readProperty$2() {
  const start = this.tokenStart;
  if (this.tokenType === types$1m.Delim) switch (this.charCodeAt(this.tokenStart)) {
   case ASTERISK$a:
   case DOLLARSIGN$2:
   case PLUSSIGN$d:
   case NUMBERSIGN$7:
   case AMPERSAND$5:
    this.next();
    break;

   case SOLIDUS$9:
    this.next(), this.isDelim(SOLIDUS$9) && this.next();
  }
  return this.tokenType === types$1m.Hash ? this.eat(types$1m.Hash) : this.eat(types$1m.Ident), 
  this.substrToCursor(start);
}

function getImportant$1() {
  this.eat(types$1m.Delim), this.skipSC();
  const important = this.consume(types$1m.Ident);
  return "important" === important || important;
}

Declaration$7.generate = function(node) {
  this.token(types$1m.Ident, node.property), this.token(types$1m.Colon, ":"), this.node(node.value), 
  node.important && (this.token(types$1m.Delim, "!"), this.token(types$1m.Ident, !0 === node.important ? "important" : node.important));
}, Declaration$7.name = "Declaration", Declaration$7.parse = function() {
  const start = this.tokenStart, startToken = this.tokenIndex, property = readProperty$2.call(this), customProperty = names$a.isCustomProperty(property), parseValue = customProperty ? this.parseCustomProperty : this.parseValue, consumeRaw = customProperty ? consumeCustomPropertyRaw$1 : consumeValueRaw$1;
  let value, important = !1;
  this.skipSC(), this.eat(types$1m.Colon);
  const valueStart = this.tokenIndex;
  if (customProperty || this.skipSC(), value = parseValue ? this.parseWithFallback(consumeValue$1, consumeRaw) : consumeRaw.call(this, this.tokenIndex), 
  customProperty && "Value" === value.type && value.children.isEmpty) for (let offset = valueStart - this.tokenIndex; offset <= 0; offset++) if (this.lookupType(offset) === types$1m.WhiteSpace) {
    value.children.appendData({
      type: "WhiteSpace",
      loc: null,
      value: " "
    });
    break;
  }
  return this.isDelim(EXCLAMATIONMARK$5) && (important = getImportant$1.call(this), 
  this.skipSC()), !1 === this.eof && this.tokenType !== types$1m.Semicolon && !1 === this.isBalanceEdge(startToken) && this.error(), 
  {
    type: "Declaration",
    loc: this.getLocation(start, this.tokenStart),
    important,
    property,
    value
  };
}, Declaration$7.structure = structure$17, Declaration$7.walkContext = "declaration";

var DeclarationList$5 = {};

const types$1l = types$1I;

function consumeRaw$9(startToken) {
  return this.Raw(startToken, this.consumeUntilSemicolonIncluded, !0);
}

DeclarationList$5.generate = function(node) {
  this.children(node, (prev => {
    "Declaration" === prev.type && this.token(types$1l.Semicolon, ";");
  }));
}, DeclarationList$5.name = "DeclarationList", DeclarationList$5.parse = function() {
  const children = this.createList();
  for (;!this.eof; ) switch (this.tokenType) {
   case types$1l.WhiteSpace:
   case types$1l.Comment:
   case types$1l.Semicolon:
    this.next();
    break;

   case types$1l.AtKeyword:
    children.push(this.parseWithFallback(this.Atrule.bind(this, !0), consumeRaw$9));
    break;

   default:
    this.isDelim(38) ? children.push(this.parseWithFallback(this.Rule, consumeRaw$9)) : children.push(this.parseWithFallback(this.Declaration, consumeRaw$9));
  }
  return {
    type: "DeclarationList",
    loc: this.getLocationFromList(children),
    children
  };
}, DeclarationList$5.structure = {
  children: [ [ "Declaration", "Atrule", "Rule" ] ]
};

var Dimension$8 = {};

const types$1k = types$1I, structure$15 = {
  value: String,
  unit: String
};

Dimension$8.generate = function(node) {
  this.token(types$1k.Dimension, node.value + node.unit);
}, Dimension$8.name = "Dimension", Dimension$8.parse = function() {
  const start = this.tokenStart, value = this.consumeNumber(types$1k.Dimension);
  return {
    type: "Dimension",
    loc: this.getLocation(start, this.tokenStart),
    value,
    unit: this.substring(start + value.length, this.tokenStart)
  };
}, Dimension$8.structure = structure$15;

var _Function$1 = {};

const types$1j = types$1I, structure$14 = {
  name: String,
  children: [ [] ]
};

_Function$1.generate = function(node) {
  this.token(types$1j.Function, node.name + "("), this.children(node), this.token(types$1j.RightParenthesis, ")");
}, _Function$1.name = "Function", _Function$1.parse = function(readSequence, recognizer) {
  const start = this.tokenStart, name = this.consumeFunctionName(), nameLowerCase = name.toLowerCase();
  let children;
  return children = recognizer.hasOwnProperty(nameLowerCase) ? recognizer[nameLowerCase].call(this, recognizer) : readSequence.call(this, recognizer), 
  this.eof || this.eat(types$1j.RightParenthesis), {
    type: "Function",
    loc: this.getLocation(start, this.tokenStart),
    name,
    children
  };
}, _Function$1.structure = structure$14, _Function$1.walkContext = "function";

var Hash$6 = {};

const types$1i = types$1I, structure$13 = {
  value: String
};

Hash$6.generate = function(node) {
  this.token(types$1i.Hash, "#" + node.value);
}, Hash$6.name = "Hash", Hash$6.parse = function() {
  const start = this.tokenStart;
  return this.eat(types$1i.Hash), {
    type: "Hash",
    loc: this.getLocation(start, this.tokenStart),
    value: this.substrToCursor(start + 1)
  };
}, Hash$6.structure = structure$13, Hash$6.xxx = "XXX";

var Identifier$5 = {};

const types$1h = types$1I, structure$12 = {
  name: String
};

Identifier$5.generate = function(node) {
  this.token(types$1h.Ident, node.name);
}, Identifier$5.name = "Identifier", Identifier$5.parse = function() {
  return {
    type: "Identifier",
    loc: this.getLocation(this.tokenStart, this.tokenEnd),
    name: this.consume(types$1h.Ident)
  };
}, Identifier$5.structure = structure$12;

var IdSelector$5 = {};

const types$1g = types$1I, structure$11 = {
  name: String
};

IdSelector$5.generate = function(node) {
  this.token(types$1g.Delim, "#" + node.name);
}, IdSelector$5.name = "IdSelector", IdSelector$5.parse = function() {
  const start = this.tokenStart;
  return this.eat(types$1g.Hash), {
    type: "IdSelector",
    loc: this.getLocation(start, this.tokenStart),
    name: this.substrToCursor(start + 1)
  };
}, IdSelector$5.structure = structure$11;

var MediaFeature$5 = {};

const types$1f = types$1I, structure$10 = {
  name: String,
  value: [ "Identifier", "Number", "Dimension", "Ratio", null ]
};

MediaFeature$5.generate = function(node) {
  this.token(types$1f.LeftParenthesis, "("), this.token(types$1f.Ident, node.name), 
  null !== node.value && (this.token(types$1f.Colon, ":"), this.node(node.value)), 
  this.token(types$1f.RightParenthesis, ")");
}, MediaFeature$5.name = "MediaFeature", MediaFeature$5.parse = function() {
  const start = this.tokenStart;
  let name, value = null;
  if (this.eat(types$1f.LeftParenthesis), this.skipSC(), name = this.consume(types$1f.Ident), 
  this.skipSC(), this.tokenType !== types$1f.RightParenthesis) {
    switch (this.eat(types$1f.Colon), this.skipSC(), this.tokenType) {
     case types$1f.Number:
      value = this.lookupNonWSType(1) === types$1f.Delim ? this.Ratio() : this.Number();
      break;

     case types$1f.Dimension:
      value = this.Dimension();
      break;

     case types$1f.Ident:
      value = this.Identifier();
      break;

     default:
      this.error("Number, dimension, ratio or identifier is expected");
    }
    this.skipSC();
  }
  return this.eat(types$1f.RightParenthesis), {
    type: "MediaFeature",
    loc: this.getLocation(start, this.tokenStart),
    name,
    value
  };
}, MediaFeature$5.structure = structure$10;

var MediaQuery$5 = {};

const types$1e = types$1I;

MediaQuery$5.generate = function(node) {
  this.children(node);
}, MediaQuery$5.name = "MediaQuery", MediaQuery$5.parse = function() {
  const children = this.createList();
  let child = null;
  this.skipSC();
  scan: for (;!this.eof; ) {
    switch (this.tokenType) {
     case types$1e.Comment:
     case types$1e.WhiteSpace:
      this.next();
      continue;

     case types$1e.Ident:
      child = this.Identifier();
      break;

     case types$1e.LeftParenthesis:
      child = this.MediaFeature();
      break;

     default:
      break scan;
    }
    children.push(child);
  }
  return null === child && this.error("Identifier or parenthesis is expected"), {
    type: "MediaQuery",
    loc: this.getLocationFromList(children),
    children
  };
}, MediaQuery$5.structure = {
  children: [ [ "Identifier", "MediaFeature", "WhiteSpace" ] ]
};

var MediaQueryList$5 = {};

const types$1d = types$1I;

MediaQueryList$5.generate = function(node) {
  this.children(node, (() => this.token(types$1d.Comma, ",")));
}, MediaQueryList$5.name = "MediaQueryList", MediaQueryList$5.parse = function() {
  const children = this.createList();
  for (this.skipSC(); !this.eof && (children.push(this.MediaQuery()), this.tokenType === types$1d.Comma); ) this.next();
  return {
    type: "MediaQueryList",
    loc: this.getLocationFromList(children),
    children
  };
}, MediaQueryList$5.structure = {
  children: [ [ "MediaQuery" ] ]
};

var NestingSelector$2 = {};

const types$1c = types$1I;

NestingSelector$2.generate = function() {
  this.token(types$1c.Delim, "&");
}, NestingSelector$2.name = "NestingSelector", NestingSelector$2.parse = function() {
  const start = this.tokenStart;
  return this.eatDelim(38), {
    type: "NestingSelector",
    loc: this.getLocation(start, this.tokenStart)
  };
}, NestingSelector$2.structure = {};

var Nth$5 = {};

const types$1b = types$1I;

Nth$5.generate = function(node) {
  this.node(node.nth), null !== node.selector && (this.token(types$1b.Ident, "of"), 
  this.node(node.selector));
}, Nth$5.name = "Nth", Nth$5.parse = function() {
  this.skipSC();
  const start = this.tokenStart;
  let nth, end = start, selector = null;
  return nth = this.lookupValue(0, "odd") || this.lookupValue(0, "even") ? this.Identifier() : this.AnPlusB(), 
  end = this.tokenStart, this.skipSC(), this.lookupValue(0, "of") && (this.next(), 
  selector = this.SelectorList(), end = this.tokenStart), {
    type: "Nth",
    loc: this.getLocation(start, end),
    nth,
    selector
  };
}, Nth$5.structure = {
  nth: [ "AnPlusB", "Identifier" ],
  selector: [ "SelectorList", null ]
};

var _Number$6 = {};

const types$1a = types$1I, structure$X = {
  value: String
};

_Number$6.generate = function(node) {
  this.token(types$1a.Number, node.value);
}, _Number$6.name = "Number", _Number$6.parse = function() {
  return {
    type: "Number",
    loc: this.getLocation(this.tokenStart, this.tokenEnd),
    value: this.consume(types$1a.Number)
  };
}, _Number$6.structure = structure$X;

var Operator$5 = {};

const structure$W = {
  value: String
};

Operator$5.generate = function(node) {
  this.tokenize(node.value);
}, Operator$5.name = "Operator", Operator$5.parse = function() {
  const start = this.tokenStart;
  return this.next(), {
    type: "Operator",
    loc: this.getLocation(start, this.tokenStart),
    value: this.substrToCursor(start)
  };
}, Operator$5.structure = structure$W;

var Parentheses$5 = {};

const types$19 = types$1I;

Parentheses$5.generate = function(node) {
  this.token(types$19.LeftParenthesis, "("), this.children(node), this.token(types$19.RightParenthesis, ")");
}, Parentheses$5.name = "Parentheses", Parentheses$5.parse = function(readSequence, recognizer) {
  const start = this.tokenStart;
  let children = null;
  return this.eat(types$19.LeftParenthesis), children = readSequence.call(this, recognizer), 
  this.eof || this.eat(types$19.RightParenthesis), {
    type: "Parentheses",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, Parentheses$5.structure = {
  children: [ [] ]
};

var Percentage$8 = {};

const types$18 = types$1I, structure$U = {
  value: String
};

Percentage$8.generate = function(node) {
  this.token(types$18.Percentage, node.value + "%");
}, Percentage$8.name = "Percentage", Percentage$8.parse = function() {
  return {
    type: "Percentage",
    loc: this.getLocation(this.tokenStart, this.tokenEnd),
    value: this.consumeNumber(types$18.Percentage)
  };
}, Percentage$8.structure = structure$U;

var PseudoClassSelector$5 = {};

const types$17 = types$1I, structure$T = {
  name: String,
  children: [ [ "Raw" ], null ]
};

PseudoClassSelector$5.generate = function(node) {
  this.token(types$17.Colon, ":"), null === node.children ? this.token(types$17.Ident, node.name) : (this.token(types$17.Function, node.name + "("), 
  this.children(node), this.token(types$17.RightParenthesis, ")"));
}, PseudoClassSelector$5.name = "PseudoClassSelector", PseudoClassSelector$5.parse = function() {
  const start = this.tokenStart;
  let name, nameLowerCase, children = null;
  return this.eat(types$17.Colon), this.tokenType === types$17.Function ? (name = this.consumeFunctionName(), 
  nameLowerCase = name.toLowerCase(), hasOwnProperty.call(this.pseudo, nameLowerCase) ? (this.skipSC(), 
  children = this.pseudo[nameLowerCase].call(this), this.skipSC()) : (children = this.createList(), 
  children.push(this.Raw(this.tokenIndex, null, !1))), this.eat(types$17.RightParenthesis)) : name = this.consume(types$17.Ident), 
  {
    type: "PseudoClassSelector",
    loc: this.getLocation(start, this.tokenStart),
    name,
    children
  };
}, PseudoClassSelector$5.structure = structure$T, PseudoClassSelector$5.walkContext = "function";

var PseudoElementSelector$5 = {};

const types$16 = types$1I, structure$S = {
  name: String,
  children: [ [ "Raw" ], null ]
};

PseudoElementSelector$5.generate = function(node) {
  this.token(types$16.Colon, ":"), this.token(types$16.Colon, ":"), null === node.children ? this.token(types$16.Ident, node.name) : (this.token(types$16.Function, node.name + "("), 
  this.children(node), this.token(types$16.RightParenthesis, ")"));
}, PseudoElementSelector$5.name = "PseudoElementSelector", PseudoElementSelector$5.parse = function() {
  const start = this.tokenStart;
  let name, nameLowerCase, children = null;
  return this.eat(types$16.Colon), this.eat(types$16.Colon), this.tokenType === types$16.Function ? (name = this.consumeFunctionName(), 
  nameLowerCase = name.toLowerCase(), hasOwnProperty.call(this.pseudo, nameLowerCase) ? (this.skipSC(), 
  children = this.pseudo[nameLowerCase].call(this), this.skipSC()) : (children = this.createList(), 
  children.push(this.Raw(this.tokenIndex, null, !1))), this.eat(types$16.RightParenthesis)) : name = this.consume(types$16.Ident), 
  {
    type: "PseudoElementSelector",
    loc: this.getLocation(start, this.tokenStart),
    name,
    children
  };
}, PseudoElementSelector$5.structure = structure$S, PseudoElementSelector$5.walkContext = "function";

var Ratio$5 = {};

const types$15 = types$1I, charCodeDefinitions$h = charCodeDefinitions$p, FULLSTOP$4 = 0x002E;

function consumeNumber$2() {
  this.skipSC();
  const value = this.consume(types$15.Number);
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    charCodeDefinitions$h.isDigit(code) || code === FULLSTOP$4 || this.error("Unsigned number is expected", this.tokenStart - value.length + i);
  }
  return 0 === Number(value) && this.error("Zero number is not allowed", this.tokenStart - value.length), 
  value;
}

const structure$R = {
  left: String,
  right: String
};

Ratio$5.generate = function(node) {
  this.token(types$15.Number, node.left), this.token(types$15.Delim, "/"), this.token(types$15.Number, node.right);
}, Ratio$5.name = "Ratio", Ratio$5.parse = function() {
  const start = this.tokenStart, left = consumeNumber$2.call(this);
  let right;
  return this.skipSC(), this.eatDelim(47), right = consumeNumber$2.call(this), {
    type: "Ratio",
    loc: this.getLocation(start, this.tokenStart),
    left,
    right
  };
}, Ratio$5.structure = structure$R;

var Raw$7 = {};

const types$14 = types$1I;

function getOffsetExcludeWS$1() {
  return this.tokenIndex > 0 && this.lookupType(-1) === types$14.WhiteSpace ? this.tokenIndex > 1 ? this.getTokenStart(this.tokenIndex - 1) : this.firstCharOffset : this.tokenStart;
}

const structure$Q = {
  value: String
};

Raw$7.generate = function(node) {
  this.tokenize(node.value);
}, Raw$7.name = "Raw", Raw$7.parse = function(startToken, consumeUntil, excludeWhiteSpace) {
  const startOffset = this.getTokenStart(startToken);
  let endOffset;
  return this.skipUntilBalanced(startToken, consumeUntil || this.consumeUntilBalanceEnd), 
  endOffset = excludeWhiteSpace && this.tokenStart > startOffset ? getOffsetExcludeWS$1.call(this) : this.tokenStart, 
  {
    type: "Raw",
    loc: this.getLocation(startOffset, endOffset),
    value: this.substring(startOffset, endOffset)
  };
}, Raw$7.structure = structure$Q;

var Rule$7 = {};

const types$13 = types$1I;

function consumeRaw$8(startToken) {
  return this.Raw(startToken, this.consumeUntilLeftCurlyBracket, !0);
}

function consumePrelude$1() {
  const prelude = this.SelectorList();
  return "Raw" !== prelude.type && !1 === this.eof && this.tokenType !== types$13.LeftCurlyBracket && this.error(), 
  prelude;
}

Rule$7.generate = function(node) {
  this.node(node.prelude), this.node(node.block);
}, Rule$7.name = "Rule", Rule$7.parse = function() {
  const startToken = this.tokenIndex, startOffset = this.tokenStart;
  let prelude, block;
  return prelude = this.parseRulePrelude ? this.parseWithFallback(consumePrelude$1, consumeRaw$8) : consumeRaw$8.call(this, startToken), 
  block = this.Block(!0), {
    type: "Rule",
    loc: this.getLocation(startOffset, this.tokenStart),
    prelude,
    block
  };
}, Rule$7.structure = {
  prelude: [ "SelectorList", "Raw" ],
  block: [ "Block" ]
}, Rule$7.walkContext = "rule";

var Selector$7 = {};

Selector$7.generate = function(node) {
  this.children(node);
}, Selector$7.name = "Selector", Selector$7.parse = function() {
  const children = this.readSequence(this.scope.Selector);
  return null === this.getFirstListNode(children) && this.error("Selector is expected"), 
  {
    type: "Selector",
    loc: this.getLocationFromList(children),
    children
  };
}, Selector$7.structure = {
  children: [ [ "TypeSelector", "IdSelector", "ClassSelector", "AttributeSelector", "PseudoClassSelector", "PseudoElementSelector", "Combinator", "WhiteSpace" ] ]
};

var SelectorList$5 = {};

const types$12 = types$1I;

SelectorList$5.generate = function(node) {
  this.children(node, (() => this.token(types$12.Comma, ",")));
}, SelectorList$5.name = "SelectorList", SelectorList$5.parse = function() {
  const children = this.createList();
  for (;!this.eof && (children.push(this.Selector()), this.tokenType === types$12.Comma); ) this.next();
  return {
    type: "SelectorList",
    loc: this.getLocationFromList(children),
    children
  };
}, SelectorList$5.structure = {
  children: [ [ "Selector", "Raw" ] ]
}, SelectorList$5.walkContext = "selector";

var _String$1 = {}, string$7 = {};

const charCodeDefinitions$g = charCodeDefinitions$p, utils$n = utils$u;

string$7.decode = function(str) {
  const len = str.length, firstChar = str.charCodeAt(0), start = 34 === firstChar || 39 === firstChar ? 1 : 0, end = 1 === start && len > 1 && str.charCodeAt(len - 1) === firstChar ? len - 2 : len - 1;
  let decoded = "";
  for (let i = start; i <= end; i++) {
    let code = str.charCodeAt(i);
    if (92 === code) {
      if (i === end) {
        i !== len - 1 && (decoded = str.substr(i + 1));
        break;
      }
      if (code = str.charCodeAt(++i), charCodeDefinitions$g.isValidEscape(92, code)) {
        const escapeStart = i - 1, escapeEnd = utils$n.consumeEscaped(str, escapeStart);
        i = escapeEnd - 1, decoded += utils$n.decodeEscaped(str.substring(escapeStart + 1, escapeEnd));
      } else 0x000d === code && 0x000a === str.charCodeAt(i + 1) && i++;
    } else decoded += str[i];
  }
  return decoded;
}, string$7.encode = function(str, apostrophe) {
  const quote = apostrophe ? "'" : '"', quoteCode = apostrophe ? 39 : 34;
  let encoded = "", wsBeforeHexIsNeeded = !1;
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    0x0000 !== code ? code <= 0x001f || 0x007F === code ? (encoded += "\\" + code.toString(16), 
    wsBeforeHexIsNeeded = !0) : code === quoteCode || 92 === code ? (encoded += "\\" + str.charAt(i), 
    wsBeforeHexIsNeeded = !1) : (wsBeforeHexIsNeeded && (charCodeDefinitions$g.isHexDigit(code) || charCodeDefinitions$g.isWhiteSpace(code)) && (encoded += " "), 
    encoded += str.charAt(i), wsBeforeHexIsNeeded = !1) : encoded += "�";
  }
  return quote + encoded + quote;
};

const string$6 = string$7, types$11 = types$1I, structure$M = {
  value: String
};

_String$1.generate = function(node) {
  this.token(types$11.String, string$6.encode(node.value));
}, _String$1.name = "String", _String$1.parse = function() {
  return {
    type: "String",
    loc: this.getLocation(this.tokenStart, this.tokenEnd),
    value: string$6.decode(this.consume(types$11.String))
  };
}, _String$1.structure = structure$M;

var StyleSheet$5 = {};

const types$10 = types$1I;

function consumeRaw$7(startToken) {
  return this.Raw(startToken, null, !1);
}

StyleSheet$5.generate = function(node) {
  this.children(node);
}, StyleSheet$5.name = "StyleSheet", StyleSheet$5.parse = function() {
  const start = this.tokenStart, children = this.createList();
  let child;
  for (;!this.eof; ) {
    switch (this.tokenType) {
     case types$10.WhiteSpace:
      this.next();
      continue;

     case types$10.Comment:
      if (33 !== this.charCodeAt(this.tokenStart + 2)) {
        this.next();
        continue;
      }
      child = this.Comment();
      break;

     case types$10.CDO:
      child = this.CDO();
      break;

     case types$10.CDC:
      child = this.CDC();
      break;

     case types$10.AtKeyword:
      child = this.parseWithFallback(this.Atrule, consumeRaw$7);
      break;

     default:
      child = this.parseWithFallback(this.Rule, consumeRaw$7);
    }
    children.push(child);
  }
  return {
    type: "StyleSheet",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, StyleSheet$5.structure = {
  children: [ [ "Comment", "CDO", "CDC", "Atrule", "Rule", "Raw" ] ]
}, StyleSheet$5.walkContext = "stylesheet";

var TypeSelector$7 = {};

const types$$ = types$1I, ASTERISK$9 = 0x002A;

function eatIdentifierOrAsterisk$1() {
  this.tokenType !== types$$.Ident && !1 === this.isDelim(ASTERISK$9) && this.error("Identifier or asterisk is expected"), 
  this.next();
}

const structure$K = {
  name: String
};

TypeSelector$7.generate = function(node) {
  this.tokenize(node.name);
}, TypeSelector$7.name = "TypeSelector", TypeSelector$7.parse = function() {
  const start = this.tokenStart;
  return this.isDelim(124) ? (this.next(), eatIdentifierOrAsterisk$1.call(this)) : (eatIdentifierOrAsterisk$1.call(this), 
  this.isDelim(124) && (this.next(), eatIdentifierOrAsterisk$1.call(this))), {
    type: "TypeSelector",
    loc: this.getLocation(start, this.tokenStart),
    name: this.substrToCursor(start)
  };
}, TypeSelector$7.structure = structure$K;

var UnicodeRange$5 = {};

const types$_ = types$1I, charCodeDefinitions$f = charCodeDefinitions$p, PLUSSIGN$c = 0x002B, HYPHENMINUS$8 = 0x002D, QUESTIONMARK$3 = 0x003F;

function eatHexSequence$1(offset, allowDash) {
  let len = 0;
  for (let pos = this.tokenStart + offset; pos < this.tokenEnd; pos++) {
    const code = this.charCodeAt(pos);
    if (code === HYPHENMINUS$8 && allowDash && 0 !== len) return eatHexSequence$1.call(this, offset + len + 1, !1), 
    -1;
    charCodeDefinitions$f.isHexDigit(code) || this.error(allowDash && 0 !== len ? "Hyphen minus" + (len < 6 ? " or hex digit" : "") + " is expected" : len < 6 ? "Hex digit is expected" : "Unexpected input", pos), 
    ++len > 6 && this.error("Too many hex digits", pos);
  }
  return this.next(), len;
}

function eatQuestionMarkSequence$1(max) {
  let count = 0;
  for (;this.isDelim(QUESTIONMARK$3); ) ++count > max && this.error("Too many question marks"), 
  this.next();
}

function startsWith$2(code) {
  this.charCodeAt(this.tokenStart) !== code && this.error((code === PLUSSIGN$c ? "Plus sign" : "Hyphen minus") + " is expected");
}

function scanUnicodeRange$1() {
  let hexLength = 0;
  switch (this.tokenType) {
   case types$_.Number:
    if (hexLength = eatHexSequence$1.call(this, 1, !0), this.isDelim(QUESTIONMARK$3)) {
      eatQuestionMarkSequence$1.call(this, 6 - hexLength);
      break;
    }
    if (this.tokenType === types$_.Dimension || this.tokenType === types$_.Number) {
      startsWith$2.call(this, HYPHENMINUS$8), eatHexSequence$1.call(this, 1, !1);
      break;
    }
    break;

   case types$_.Dimension:
    hexLength = eatHexSequence$1.call(this, 1, !0), hexLength > 0 && eatQuestionMarkSequence$1.call(this, 6 - hexLength);
    break;

   default:
    if (this.eatDelim(PLUSSIGN$c), this.tokenType === types$_.Ident) {
      hexLength = eatHexSequence$1.call(this, 0, !0), hexLength > 0 && eatQuestionMarkSequence$1.call(this, 6 - hexLength);
      break;
    }
    if (this.isDelim(QUESTIONMARK$3)) {
      this.next(), eatQuestionMarkSequence$1.call(this, 5);
      break;
    }
    this.error("Hex digit or question mark is expected");
  }
}

const structure$J = {
  value: String
};

UnicodeRange$5.generate = function(node) {
  this.tokenize(node.value);
}, UnicodeRange$5.name = "UnicodeRange", UnicodeRange$5.parse = function() {
  const start = this.tokenStart;
  return this.eatIdent("u"), scanUnicodeRange$1.call(this), {
    type: "UnicodeRange",
    loc: this.getLocation(start, this.tokenStart),
    value: this.substrToCursor(start)
  };
}, UnicodeRange$5.structure = structure$J;

var Url$8 = {}, url$5 = {};

const charCodeDefinitions$e = charCodeDefinitions$p, utils$m = utils$u;

url$5.decode = function(str) {
  const len = str.length;
  let start = 4, end = 41 === str.charCodeAt(len - 1) ? len - 2 : len - 1, decoded = "";
  for (;start < end && charCodeDefinitions$e.isWhiteSpace(str.charCodeAt(start)); ) start++;
  for (;start < end && charCodeDefinitions$e.isWhiteSpace(str.charCodeAt(end)); ) end--;
  for (let i = start; i <= end; i++) {
    let code = str.charCodeAt(i);
    if (92 === code) {
      if (i === end) {
        i !== len - 1 && (decoded = str.substr(i + 1));
        break;
      }
      if (code = str.charCodeAt(++i), charCodeDefinitions$e.isValidEscape(92, code)) {
        const escapeStart = i - 1, escapeEnd = utils$m.consumeEscaped(str, escapeStart);
        i = escapeEnd - 1, decoded += utils$m.decodeEscaped(str.substring(escapeStart + 1, escapeEnd));
      } else 0x000d === code && 0x000a === str.charCodeAt(i + 1) && i++;
    } else decoded += str[i];
  }
  return decoded;
}, url$5.encode = function(str) {
  let encoded = "", wsBeforeHexIsNeeded = !1;
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    0x0000 !== code ? code <= 0x001f || 0x007F === code ? (encoded += "\\" + code.toString(16), 
    wsBeforeHexIsNeeded = !0) : 32 === code || 92 === code || 34 === code || 39 === code || 40 === code || 41 === code ? (encoded += "\\" + str.charAt(i), 
    wsBeforeHexIsNeeded = !1) : (wsBeforeHexIsNeeded && charCodeDefinitions$e.isHexDigit(code) && (encoded += " "), 
    encoded += str.charAt(i), wsBeforeHexIsNeeded = !1) : encoded += "�";
  }
  return "url(" + encoded + ")";
};

const url$4 = url$5, string$5 = string$7, types$Z = types$1I, structure$I = {
  value: String
};

Url$8.generate = function(node) {
  this.token(types$Z.Url, url$4.encode(node.value));
}, Url$8.name = "Url", Url$8.parse = function() {
  const start = this.tokenStart;
  let value;
  switch (this.tokenType) {
   case types$Z.Url:
    value = url$4.decode(this.consume(types$Z.Url));
    break;

   case types$Z.Function:
    this.cmpStr(this.tokenStart, this.tokenEnd, "url(") || this.error("Function name must be `url`"), 
    this.eat(types$Z.Function), this.skipSC(), value = string$5.decode(this.consume(types$Z.String)), 
    this.skipSC(), this.eof || this.eat(types$Z.RightParenthesis);
    break;

   default:
    this.error("Url or Function is expected");
  }
  return {
    type: "Url",
    loc: this.getLocation(start, this.tokenStart),
    value
  };
}, Url$8.structure = structure$I;

var Value$7 = {};

Value$7.generate = function(node) {
  this.children(node);
}, Value$7.name = "Value", Value$7.parse = function() {
  const start = this.tokenStart, children = this.readSequence(this.scope.Value);
  return {
    type: "Value",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, Value$7.structure = {
  children: [ [] ]
};

var WhiteSpace$8 = {};

const types$Y = types$1I, SPACE$4 = Object.freeze({
  type: "WhiteSpace",
  loc: null,
  value: " "
}), structure$G = {
  value: String
};

WhiteSpace$8.generate = function(node) {
  this.token(types$Y.WhiteSpace, node.value);
}, WhiteSpace$8.name = "WhiteSpace", WhiteSpace$8.parse = function() {
  return this.eat(types$Y.WhiteSpace), SPACE$4;
}, WhiteSpace$8.structure = structure$G;

const AnPlusB$4 = AnPlusB$5, Atrule$8 = Atrule$9, AtrulePrelude$4 = AtrulePrelude$5, AttributeSelector$6 = AttributeSelector$7, Block$4 = Block$5, Brackets$4 = Brackets$5, CDC$5 = CDC$6, CDO$5 = CDO$6, ClassSelector$4 = ClassSelector$5, Combinator$4 = Combinator$5, Comment$7 = Comment$8, Declaration$6 = Declaration$7, DeclarationList$4 = DeclarationList$5, Dimension$7 = Dimension$8, Function$5 = _Function$1, Hash$5 = Hash$6, Identifier$4 = Identifier$5, IdSelector$4 = IdSelector$5, MediaFeature$4 = MediaFeature$5, MediaQuery$4 = MediaQuery$5, MediaQueryList$4 = MediaQueryList$5, NestingSelector$1 = NestingSelector$2, Nth$4 = Nth$5, Number$1$2 = _Number$6, Operator$4 = Operator$5, Parentheses$4 = Parentheses$5, Percentage$7 = Percentage$8, PseudoClassSelector$4 = PseudoClassSelector$5, PseudoElementSelector$4 = PseudoElementSelector$5, Ratio$4 = Ratio$5, Raw$6 = Raw$7, Rule$6 = Rule$7, Selector$6 = Selector$7, SelectorList$4 = SelectorList$5, String$1$2 = _String$1, StyleSheet$4 = StyleSheet$5, TypeSelector$6 = TypeSelector$7, UnicodeRange$4 = UnicodeRange$5, Url$7 = Url$8, Value$6 = Value$7, WhiteSpace$7 = WhiteSpace$8;

node$1.AnPlusB = AnPlusB$4, node$1.Atrule = Atrule$8, node$1.AtrulePrelude = AtrulePrelude$4, 
node$1.AttributeSelector = AttributeSelector$6, node$1.Block = Block$4, node$1.Brackets = Brackets$4, 
node$1.CDC = CDC$5, node$1.CDO = CDO$5, node$1.ClassSelector = ClassSelector$4, 
node$1.Combinator = Combinator$4, node$1.Comment = Comment$7, node$1.Declaration = Declaration$6, 
node$1.DeclarationList = DeclarationList$4, node$1.Dimension = Dimension$7, node$1.Function = Function$5, 
node$1.Hash = Hash$5, node$1.Identifier = Identifier$4, node$1.IdSelector = IdSelector$4, 
node$1.MediaFeature = MediaFeature$4, node$1.MediaQuery = MediaQuery$4, node$1.MediaQueryList = MediaQueryList$4, 
node$1.NestingSelector = NestingSelector$1, node$1.Nth = Nth$4, node$1.Number = Number$1$2, 
node$1.Operator = Operator$4, node$1.Parentheses = Parentheses$4, node$1.Percentage = Percentage$7, 
node$1.PseudoClassSelector = PseudoClassSelector$4, node$1.PseudoElementSelector = PseudoElementSelector$4, 
node$1.Ratio = Ratio$4, node$1.Raw = Raw$6, node$1.Rule = Rule$6, node$1.Selector = Selector$6, 
node$1.SelectorList = SelectorList$4, node$1.String = String$1$2, node$1.StyleSheet = StyleSheet$4, 
node$1.TypeSelector = TypeSelector$6, node$1.UnicodeRange = UnicodeRange$4, node$1.Url = Url$7, 
node$1.Value = Value$6, node$1.WhiteSpace = WhiteSpace$7;

var lexer$6 = {
  generic: !0,
  ...{
    "generic": !0,
    "units": {
      "angle": [ "deg", "grad", "rad", "turn" ],
      "decibel": [ "db" ],
      "flex": [ "fr" ],
      "frequency": [ "hz", "khz" ],
      "length": [ "cm", "mm", "q", "in", "pt", "pc", "px", "em", "rem", "ex", "rex", "cap", "rcap", "ch", "rch", "ic", "ric", "lh", "rlh", "vw", "svw", "lvw", "dvw", "vh", "svh", "lvh", "dvh", "vi", "svi", "lvi", "dvi", "vb", "svb", "lvb", "dvb", "vmin", "svmin", "lvmin", "dvmin", "vmax", "svmax", "lvmax", "dvmax", "cqw", "cqh", "cqi", "cqb", "cqmin", "cqmax" ],
      "resolution": [ "dpi", "dpcm", "dppx", "x" ],
      "semitones": [ "st" ],
      "time": [ "s", "ms" ]
    },
    "types": {
      "abs()": "abs( <calc-sum> )",
      "absolute-size": "xx-small|x-small|small|medium|large|x-large|xx-large|xxx-large",
      "acos()": "acos( <calc-sum> )",
      "alpha-value": "<number>|<percentage>",
      "angle-percentage": "<angle>|<percentage>",
      "angular-color-hint": "<angle-percentage>",
      "angular-color-stop": "<color>&&<color-stop-angle>?",
      "angular-color-stop-list": "[<angular-color-stop> [, <angular-color-hint>]?]# , <angular-color-stop>",
      "animateable-feature": "scroll-position|contents|<custom-ident>",
      "asin()": "asin( <calc-sum> )",
      "atan()": "atan( <calc-sum> )",
      "atan2()": "atan2( <calc-sum> , <calc-sum> )",
      "attachment": "scroll|fixed|local",
      "attr()": "attr( <attr-name> <type-or-unit>? [, <attr-fallback>]? )",
      "attr-matcher": "['~'|'|'|'^'|'$'|'*']? '='",
      "attr-modifier": "i|s",
      "attribute-selector": "'[' <wq-name> ']'|'[' <wq-name> <attr-matcher> [<string-token>|<ident-token>] <attr-modifier>? ']'",
      "auto-repeat": "repeat( [auto-fill|auto-fit] , [<line-names>? <fixed-size>]+ <line-names>? )",
      "auto-track-list": "[<line-names>? [<fixed-size>|<fixed-repeat>]]* <line-names>? <auto-repeat> [<line-names>? [<fixed-size>|<fixed-repeat>]]* <line-names>?",
      "axis": "block|inline|vertical|horizontal",
      "baseline-position": "[first|last]? baseline",
      "basic-shape": "<inset()>|<circle()>|<ellipse()>|<polygon()>|<path()>",
      "bg-image": "none|<image>",
      "bg-layer": "<bg-image>||<bg-position> [/ <bg-size>]?||<repeat-style>||<attachment>||<box>||<box>",
      "bg-position": "[[left|center|right|top|bottom|<length-percentage>]|[left|center|right|<length-percentage>] [top|center|bottom|<length-percentage>]|[center|[left|right] <length-percentage>?]&&[center|[top|bottom] <length-percentage>?]]",
      "bg-size": "[<length-percentage>|auto]{1,2}|cover|contain",
      "blur()": "blur( <length> )",
      "blend-mode": "normal|multiply|screen|overlay|darken|lighten|color-dodge|color-burn|hard-light|soft-light|difference|exclusion|hue|saturation|color|luminosity",
      "box": "border-box|padding-box|content-box",
      "brightness()": "brightness( <number-percentage> )",
      "calc()": "calc( <calc-sum> )",
      "calc-sum": "<calc-product> [['+'|'-'] <calc-product>]*",
      "calc-product": "<calc-value> ['*' <calc-value>|'/' <number>]*",
      "calc-value": "<number>|<dimension>|<percentage>|<calc-constant>|( <calc-sum> )",
      "calc-constant": "e|pi|infinity|-infinity|NaN",
      "cf-final-image": "<image>|<color>",
      "cf-mixing-image": "<percentage>?&&<image>",
      "circle()": "circle( [<shape-radius>]? [at <position>]? )",
      "clamp()": "clamp( <calc-sum>#{3} )",
      "class-selector": "'.' <ident-token>",
      "clip-source": "<url>",
      "color": "<rgb()>|<rgba()>|<hsl()>|<hsla()>|<hwb()>|<lab()>|<lch()>|<hex-color>|<named-color>|currentcolor|<deprecated-system-color>",
      "color-stop": "<color-stop-length>|<color-stop-angle>",
      "color-stop-angle": "<angle-percentage>{1,2}",
      "color-stop-length": "<length-percentage>{1,2}",
      "color-stop-list": "[<linear-color-stop> [, <linear-color-hint>]?]# , <linear-color-stop>",
      "combinator": "'>'|'+'|'~'|['||']",
      "common-lig-values": "[common-ligatures|no-common-ligatures]",
      "compat-auto": "searchfield|textarea|push-button|slider-horizontal|checkbox|radio|square-button|menulist|listbox|meter|progress-bar|button",
      "composite-style": "clear|copy|source-over|source-in|source-out|source-atop|destination-over|destination-in|destination-out|destination-atop|xor",
      "compositing-operator": "add|subtract|intersect|exclude",
      "compound-selector": "[<type-selector>? <subclass-selector>* [<pseudo-element-selector> <pseudo-class-selector>*]*]!",
      "compound-selector-list": "<compound-selector>#",
      "complex-selector": "<compound-selector> [<combinator>? <compound-selector>]*",
      "complex-selector-list": "<complex-selector>#",
      "conic-gradient()": "conic-gradient( [from <angle>]? [at <position>]? , <angular-color-stop-list> )",
      "contextual-alt-values": "[contextual|no-contextual]",
      "content-distribution": "space-between|space-around|space-evenly|stretch",
      "content-list": "[<string>|contents|<image>|<counter>|<quote>|<target>|<leader()>|<attr()>]+",
      "content-position": "center|start|end|flex-start|flex-end",
      "content-replacement": "<image>",
      "contrast()": "contrast( [<number-percentage>] )",
      "cos()": "cos( <calc-sum> )",
      "counter": "<counter()>|<counters()>",
      "counter()": "counter( <counter-name> , <counter-style>? )",
      "counter-name": "<custom-ident>",
      "counter-style": "<counter-style-name>|symbols( )",
      "counter-style-name": "<custom-ident>",
      "counters()": "counters( <counter-name> , <string> , <counter-style>? )",
      "cross-fade()": "cross-fade( <cf-mixing-image> , <cf-final-image>? )",
      "cubic-bezier-timing-function": "ease|ease-in|ease-out|ease-in-out|cubic-bezier( <number [0,1]> , <number> , <number [0,1]> , <number> )",
      "deprecated-system-color": "ActiveBorder|ActiveCaption|AppWorkspace|Background|ButtonFace|ButtonHighlight|ButtonShadow|ButtonText|CaptionText|GrayText|Highlight|HighlightText|InactiveBorder|InactiveCaption|InactiveCaptionText|InfoBackground|InfoText|Menu|MenuText|Scrollbar|ThreeDDarkShadow|ThreeDFace|ThreeDHighlight|ThreeDLightShadow|ThreeDShadow|Window|WindowFrame|WindowText",
      "discretionary-lig-values": "[discretionary-ligatures|no-discretionary-ligatures]",
      "display-box": "contents|none",
      "display-inside": "flow|flow-root|table|flex|grid|ruby",
      "display-internal": "table-row-group|table-header-group|table-footer-group|table-row|table-cell|table-column-group|table-column|table-caption|ruby-base|ruby-text|ruby-base-container|ruby-text-container",
      "display-legacy": "inline-block|inline-list-item|inline-table|inline-flex|inline-grid",
      "display-listitem": "<display-outside>?&&[flow|flow-root]?&&list-item",
      "display-outside": "block|inline|run-in",
      "drop-shadow()": "drop-shadow( <length>{2,3} <color>? )",
      "east-asian-variant-values": "[jis78|jis83|jis90|jis04|simplified|traditional]",
      "east-asian-width-values": "[full-width|proportional-width]",
      "element()": "element( <custom-ident> , [first|start|last|first-except]? )|element( <id-selector> )",
      "ellipse()": "ellipse( [<shape-radius>{2}]? [at <position>]? )",
      "ending-shape": "circle|ellipse",
      "env()": "env( <custom-ident> , <declaration-value>? )",
      "exp()": "exp( <calc-sum> )",
      "explicit-track-list": "[<line-names>? <track-size>]+ <line-names>?",
      "family-name": "<string>|<custom-ident>+",
      "feature-tag-value": "<string> [<integer>|on|off]?",
      "feature-type": "@stylistic|@historical-forms|@styleset|@character-variant|@swash|@ornaments|@annotation",
      "feature-value-block": "<feature-type> '{' <feature-value-declaration-list> '}'",
      "feature-value-block-list": "<feature-value-block>+",
      "feature-value-declaration": "<custom-ident> : <integer>+ ;",
      "feature-value-declaration-list": "<feature-value-declaration>",
      "feature-value-name": "<custom-ident>",
      "fill-rule": "nonzero|evenodd",
      "filter-function": "<blur()>|<brightness()>|<contrast()>|<drop-shadow()>|<grayscale()>|<hue-rotate()>|<invert()>|<opacity()>|<saturate()>|<sepia()>",
      "filter-function-list": "[<filter-function>|<url>]+",
      "final-bg-layer": "<'background-color'>||<bg-image>||<bg-position> [/ <bg-size>]?||<repeat-style>||<attachment>||<box>||<box>",
      "fixed-breadth": "<length-percentage>",
      "fixed-repeat": "repeat( [<integer [1,∞]>] , [<line-names>? <fixed-size>]+ <line-names>? )",
      "fixed-size": "<fixed-breadth>|minmax( <fixed-breadth> , <track-breadth> )|minmax( <inflexible-breadth> , <fixed-breadth> )",
      "font-stretch-absolute": "normal|ultra-condensed|extra-condensed|condensed|semi-condensed|semi-expanded|expanded|extra-expanded|ultra-expanded|<percentage>",
      "font-variant-css21": "[normal|small-caps]",
      "font-weight-absolute": "normal|bold|<number [1,1000]>",
      "frequency-percentage": "<frequency>|<percentage>",
      "general-enclosed": "[<function-token> <any-value> )]|( <ident> <any-value> )",
      "generic-family": "serif|sans-serif|cursive|fantasy|monospace|-apple-system",
      "generic-name": "serif|sans-serif|cursive|fantasy|monospace",
      "geometry-box": "<shape-box>|fill-box|stroke-box|view-box",
      "gradient": "<linear-gradient()>|<repeating-linear-gradient()>|<radial-gradient()>|<repeating-radial-gradient()>|<conic-gradient()>|<repeating-conic-gradient()>|<-legacy-gradient>",
      "grayscale()": "grayscale( <number-percentage> )",
      "grid-line": "auto|<custom-ident>|[<integer>&&<custom-ident>?]|[span&&[<integer>||<custom-ident>]]",
      "historical-lig-values": "[historical-ligatures|no-historical-ligatures]",
      "hsl()": "hsl( <hue> <percentage> <percentage> [/ <alpha-value>]? )|hsl( <hue> , <percentage> , <percentage> , <alpha-value>? )",
      "hsla()": "hsla( <hue> <percentage> <percentage> [/ <alpha-value>]? )|hsla( <hue> , <percentage> , <percentage> , <alpha-value>? )",
      "hue": "<number>|<angle>",
      "hue-rotate()": "hue-rotate( <angle> )",
      "hwb()": "hwb( [<hue>|none] [<percentage>|none] [<percentage>|none] [/ [<alpha-value>|none]]? )",
      "hypot()": "hypot( <calc-sum># )",
      "image": "<url>|<image()>|<image-set()>|<element()>|<paint()>|<cross-fade()>|<gradient>",
      "image()": "image( <image-tags>? [<image-src>? , <color>?]! )",
      "image-set()": "image-set( <image-set-option># )",
      "image-set-option": "[<image>|<string>] [<resolution>||type( <string> )]",
      "image-src": "<url>|<string>",
      "image-tags": "ltr|rtl",
      "inflexible-breadth": "<length-percentage>|min-content|max-content|auto",
      "inset()": "inset( <length-percentage>{1,4} [round <'border-radius'>]? )",
      "invert()": "invert( <number-percentage> )",
      "keyframes-name": "<custom-ident>|<string>",
      "keyframe-block": "<keyframe-selector># { <declaration-list> }",
      "keyframe-block-list": "<keyframe-block>+",
      "keyframe-selector": "from|to|<percentage>",
      "lab()": "lab( [<percentage>|<number>|none] [<percentage>|<number>|none] [<percentage>|<number>|none] [/ [<alpha-value>|none]]? )",
      "layer()": "layer( <layer-name> )",
      "layer-name": "<ident> ['.' <ident>]*",
      "lch()": "lch( [<percentage>|<number>|none] [<percentage>|<number>|none] [<hue>|none] [/ [<alpha-value>|none]]? )",
      "leader()": "leader( <leader-type> )",
      "leader-type": "dotted|solid|space|<string>",
      "length-percentage": "<length>|<percentage>",
      "line-names": "'[' <custom-ident>* ']'",
      "line-name-list": "[<line-names>|<name-repeat>]+",
      "line-style": "none|hidden|dotted|dashed|solid|double|groove|ridge|inset|outset",
      "line-width": "<length>|thin|medium|thick",
      "linear-color-hint": "<length-percentage>",
      "linear-color-stop": "<color> <color-stop-length>?",
      "linear-gradient()": "linear-gradient( [<angle>|to <side-or-corner>]? , <color-stop-list> )",
      "log()": "log( <calc-sum> , <calc-sum>? )",
      "mask-layer": "<mask-reference>||<position> [/ <bg-size>]?||<repeat-style>||<geometry-box>||[<geometry-box>|no-clip]||<compositing-operator>||<masking-mode>",
      "mask-position": "[<length-percentage>|left|center|right] [<length-percentage>|top|center|bottom]?",
      "mask-reference": "none|<image>|<mask-source>",
      "mask-source": "<url>",
      "masking-mode": "alpha|luminance|match-source",
      "matrix()": "matrix( <number>#{6} )",
      "matrix3d()": "matrix3d( <number>#{16} )",
      "max()": "max( <calc-sum># )",
      "media-and": "<media-in-parens> [and <media-in-parens>]+",
      "media-condition": "<media-not>|<media-and>|<media-or>|<media-in-parens>",
      "media-condition-without-or": "<media-not>|<media-and>|<media-in-parens>",
      "media-feature": "( [<mf-plain>|<mf-boolean>|<mf-range>] )",
      "media-in-parens": "( <media-condition> )|<media-feature>|<general-enclosed>",
      "media-not": "not <media-in-parens>",
      "media-or": "<media-in-parens> [or <media-in-parens>]+",
      "media-query": "<media-condition>|[not|only]? <media-type> [and <media-condition-without-or>]?",
      "media-query-list": "<media-query>#",
      "media-type": "<ident>",
      "mf-boolean": "<mf-name>",
      "mf-name": "<ident>",
      "mf-plain": "<mf-name> : <mf-value>",
      "mf-range": "<mf-name> ['<'|'>']? '='? <mf-value>|<mf-value> ['<'|'>']? '='? <mf-name>|<mf-value> '<' '='? <mf-name> '<' '='? <mf-value>|<mf-value> '>' '='? <mf-name> '>' '='? <mf-value>",
      "mf-value": "<number>|<dimension>|<ident>|<ratio>",
      "min()": "min( <calc-sum># )",
      "minmax()": "minmax( [<length-percentage>|min-content|max-content|auto] , [<length-percentage>|<flex>|min-content|max-content|auto] )",
      "mod()": "mod( <calc-sum> , <calc-sum> )",
      "name-repeat": "repeat( [<integer [1,∞]>|auto-fill] , <line-names>+ )",
      "named-color": "transparent|aliceblue|antiquewhite|aqua|aquamarine|azure|beige|bisque|black|blanchedalmond|blue|blueviolet|brown|burlywood|cadetblue|chartreuse|chocolate|coral|cornflowerblue|cornsilk|crimson|cyan|darkblue|darkcyan|darkgoldenrod|darkgray|darkgreen|darkgrey|darkkhaki|darkmagenta|darkolivegreen|darkorange|darkorchid|darkred|darksalmon|darkseagreen|darkslateblue|darkslategray|darkslategrey|darkturquoise|darkviolet|deeppink|deepskyblue|dimgray|dimgrey|dodgerblue|firebrick|floralwhite|forestgreen|fuchsia|gainsboro|ghostwhite|gold|goldenrod|gray|green|greenyellow|grey|honeydew|hotpink|indianred|indigo|ivory|khaki|lavender|lavenderblush|lawngreen|lemonchiffon|lightblue|lightcoral|lightcyan|lightgoldenrodyellow|lightgray|lightgreen|lightgrey|lightpink|lightsalmon|lightseagreen|lightskyblue|lightslategray|lightslategrey|lightsteelblue|lightyellow|lime|limegreen|linen|magenta|maroon|mediumaquamarine|mediumblue|mediumorchid|mediumpurple|mediumseagreen|mediumslateblue|mediumspringgreen|mediumturquoise|mediumvioletred|midnightblue|mintcream|mistyrose|moccasin|navajowhite|navy|oldlace|olive|olivedrab|orange|orangered|orchid|palegoldenrod|palegreen|paleturquoise|palevioletred|papayawhip|peachpuff|peru|pink|plum|powderblue|purple|rebeccapurple|red|rosybrown|royalblue|saddlebrown|salmon|sandybrown|seagreen|seashell|sienna|silver|skyblue|slateblue|slategray|slategrey|snow|springgreen|steelblue|tan|teal|thistle|tomato|turquoise|violet|wheat|white|whitesmoke|yellow|yellowgreen|<-non-standard-color>",
      "namespace-prefix": "<ident>",
      "ns-prefix": "[<ident-token>|'*']? '|'",
      "number-percentage": "<number>|<percentage>",
      "numeric-figure-values": "[lining-nums|oldstyle-nums]",
      "numeric-fraction-values": "[diagonal-fractions|stacked-fractions]",
      "numeric-spacing-values": "[proportional-nums|tabular-nums]",
      "nth": "<an-plus-b>|even|odd",
      "opacity()": "opacity( [<number-percentage>] )",
      "overflow-position": "unsafe|safe",
      "outline-radius": "<length>|<percentage>",
      "page-body": "<declaration>? [; <page-body>]?|<page-margin-box> <page-body>",
      "page-margin-box": "<page-margin-box-type> '{' <declaration-list> '}'",
      "page-margin-box-type": "@top-left-corner|@top-left|@top-center|@top-right|@top-right-corner|@bottom-left-corner|@bottom-left|@bottom-center|@bottom-right|@bottom-right-corner|@left-top|@left-middle|@left-bottom|@right-top|@right-middle|@right-bottom",
      "page-selector-list": "[<page-selector>#]?",
      "page-selector": "<pseudo-page>+|<ident> <pseudo-page>*",
      "page-size": "A5|A4|A3|B5|B4|JIS-B5|JIS-B4|letter|legal|ledger",
      "path()": "path( [<fill-rule> ,]? <string> )",
      "paint()": "paint( <ident> , <declaration-value>? )",
      "perspective()": "perspective( [<length [0,∞]>|none] )",
      "polygon()": "polygon( <fill-rule>? , [<length-percentage> <length-percentage>]# )",
      "position": "[[left|center|right]||[top|center|bottom]|[left|center|right|<length-percentage>] [top|center|bottom|<length-percentage>]?|[[left|right] <length-percentage>]&&[[top|bottom] <length-percentage>]]",
      "pow()": "pow( <calc-sum> , <calc-sum> )",
      "pseudo-class-selector": "':' <ident-token>|':' <function-token> <any-value> ')'",
      "pseudo-element-selector": "':' <pseudo-class-selector>",
      "pseudo-page": ": [left|right|first|blank]",
      "quote": "open-quote|close-quote|no-open-quote|no-close-quote",
      "radial-gradient()": "radial-gradient( [<ending-shape>||<size>]? [at <position>]? , <color-stop-list> )",
      "ratio": "<number [0,∞]> [/ <number [0,∞]>]?",
      "relative-selector": "<combinator>? <complex-selector>",
      "relative-selector-list": "<relative-selector>#",
      "relative-size": "larger|smaller",
      "rem()": "rem( <calc-sum> , <calc-sum> )",
      "repeat-style": "repeat-x|repeat-y|[repeat|space|round|no-repeat]{1,2}",
      "repeating-conic-gradient()": "repeating-conic-gradient( [from <angle>]? [at <position>]? , <angular-color-stop-list> )",
      "repeating-linear-gradient()": "repeating-linear-gradient( [<angle>|to <side-or-corner>]? , <color-stop-list> )",
      "repeating-radial-gradient()": "repeating-radial-gradient( [<ending-shape>||<size>]? [at <position>]? , <color-stop-list> )",
      "reversed-counter-name": "reversed( <counter-name> )",
      "rgb()": "rgb( <percentage>{3} [/ <alpha-value>]? )|rgb( <number>{3} [/ <alpha-value>]? )|rgb( <percentage>#{3} , <alpha-value>? )|rgb( <number>#{3} , <alpha-value>? )",
      "rgba()": "rgba( <percentage>{3} [/ <alpha-value>]? )|rgba( <number>{3} [/ <alpha-value>]? )|rgba( <percentage>#{3} , <alpha-value>? )|rgba( <number>#{3} , <alpha-value>? )",
      "rotate()": "rotate( [<angle>|<zero>] )",
      "rotate3d()": "rotate3d( <number> , <number> , <number> , [<angle>|<zero>] )",
      "rotateX()": "rotateX( [<angle>|<zero>] )",
      "rotateY()": "rotateY( [<angle>|<zero>] )",
      "rotateZ()": "rotateZ( [<angle>|<zero>] )",
      "round()": "round( <rounding-strategy>? , <calc-sum> , <calc-sum> )",
      "rounding-strategy": "nearest|up|down|to-zero",
      "saturate()": "saturate( <number-percentage> )",
      "scale()": "scale( [<number>|<percentage>]#{1,2} )",
      "scale3d()": "scale3d( [<number>|<percentage>]#{3} )",
      "scaleX()": "scaleX( [<number>|<percentage>] )",
      "scaleY()": "scaleY( [<number>|<percentage>] )",
      "scaleZ()": "scaleZ( [<number>|<percentage>] )",
      "scroller": "root|nearest",
      "self-position": "center|start|end|self-start|self-end|flex-start|flex-end",
      "shape-radius": "<length-percentage>|closest-side|farthest-side",
      "sign()": "sign( <calc-sum> )",
      "skew()": "skew( [<angle>|<zero>] , [<angle>|<zero>]? )",
      "skewX()": "skewX( [<angle>|<zero>] )",
      "skewY()": "skewY( [<angle>|<zero>] )",
      "sepia()": "sepia( <number-percentage> )",
      "shadow": "inset?&&<length>{2,4}&&<color>?",
      "shadow-t": "[<length>{2,3}&&<color>?]",
      "shape": "rect( <top> , <right> , <bottom> , <left> )|rect( <top> <right> <bottom> <left> )",
      "shape-box": "<box>|margin-box",
      "side-or-corner": "[left|right]||[top|bottom]",
      "sin()": "sin( <calc-sum> )",
      "single-animation": "<time>||<easing-function>||<time>||<single-animation-iteration-count>||<single-animation-direction>||<single-animation-fill-mode>||<single-animation-play-state>||[none|<keyframes-name>]",
      "single-animation-direction": "normal|reverse|alternate|alternate-reverse",
      "single-animation-fill-mode": "none|forwards|backwards|both",
      "single-animation-iteration-count": "infinite|<number>",
      "single-animation-play-state": "running|paused",
      "single-animation-timeline": "auto|none|<timeline-name>|scroll( <axis>? <scroller>? )",
      "single-transition": "[none|<single-transition-property>]||<time>||<easing-function>||<time>",
      "single-transition-property": "all|<custom-ident>",
      "size": "closest-side|farthest-side|closest-corner|farthest-corner|<length>|<length-percentage>{2}",
      "sqrt()": "sqrt( <calc-sum> )",
      "step-position": "jump-start|jump-end|jump-none|jump-both|start|end",
      "step-timing-function": "step-start|step-end|steps( <integer> [, <step-position>]? )",
      "subclass-selector": "<id-selector>|<class-selector>|<attribute-selector>|<pseudo-class-selector>",
      "supports-condition": "not <supports-in-parens>|<supports-in-parens> [and <supports-in-parens>]*|<supports-in-parens> [or <supports-in-parens>]*",
      "supports-in-parens": "( <supports-condition> )|<supports-feature>|<general-enclosed>",
      "supports-feature": "<supports-decl>|<supports-selector-fn>",
      "supports-decl": "( <declaration> )",
      "supports-selector-fn": "selector( <complex-selector> )",
      "symbol": "<string>|<image>|<custom-ident>",
      "tan()": "tan( <calc-sum> )",
      "target": "<target-counter()>|<target-counters()>|<target-text()>",
      "target-counter()": "target-counter( [<string>|<url>] , <custom-ident> , <counter-style>? )",
      "target-counters()": "target-counters( [<string>|<url>] , <custom-ident> , <string> , <counter-style>? )",
      "target-text()": "target-text( [<string>|<url>] , [content|before|after|first-letter]? )",
      "time-percentage": "<time>|<percentage>",
      "timeline-name": "<custom-ident>|<string>",
      "easing-function": "linear|<cubic-bezier-timing-function>|<step-timing-function>",
      "track-breadth": "<length-percentage>|<flex>|min-content|max-content|auto",
      "track-list": "[<line-names>? [<track-size>|<track-repeat>]]+ <line-names>?",
      "track-repeat": "repeat( [<integer [1,∞]>] , [<line-names>? <track-size>]+ <line-names>? )",
      "track-size": "<track-breadth>|minmax( <inflexible-breadth> , <track-breadth> )|fit-content( <length-percentage> )",
      "transform-function": "<matrix()>|<translate()>|<translateX()>|<translateY()>|<scale()>|<scaleX()>|<scaleY()>|<rotate()>|<skew()>|<skewX()>|<skewY()>|<matrix3d()>|<translate3d()>|<translateZ()>|<scale3d()>|<scaleZ()>|<rotate3d()>|<rotateX()>|<rotateY()>|<rotateZ()>|<perspective()>",
      "transform-list": "<transform-function>+",
      "translate()": "translate( <length-percentage> , <length-percentage>? )",
      "translate3d()": "translate3d( <length-percentage> , <length-percentage> , <length> )",
      "translateX()": "translateX( <length-percentage> )",
      "translateY()": "translateY( <length-percentage> )",
      "translateZ()": "translateZ( <length> )",
      "type-or-unit": "string|color|url|integer|number|length|angle|time|frequency|cap|ch|em|ex|ic|lh|rlh|rem|vb|vi|vw|vh|vmin|vmax|mm|Q|cm|in|pt|pc|px|deg|grad|rad|turn|ms|s|Hz|kHz|%",
      "type-selector": "<wq-name>|<ns-prefix>? '*'",
      "var()": "var( <custom-property-name> , <declaration-value>? )",
      "viewport-length": "auto|<length-percentage>",
      "visual-box": "content-box|padding-box|border-box",
      "wq-name": "<ns-prefix>? <ident-token>",
      "-legacy-gradient": "<-webkit-gradient()>|<-legacy-linear-gradient>|<-legacy-repeating-linear-gradient>|<-legacy-radial-gradient>|<-legacy-repeating-radial-gradient>",
      "-legacy-linear-gradient": "-moz-linear-gradient( <-legacy-linear-gradient-arguments> )|-webkit-linear-gradient( <-legacy-linear-gradient-arguments> )|-o-linear-gradient( <-legacy-linear-gradient-arguments> )",
      "-legacy-repeating-linear-gradient": "-moz-repeating-linear-gradient( <-legacy-linear-gradient-arguments> )|-webkit-repeating-linear-gradient( <-legacy-linear-gradient-arguments> )|-o-repeating-linear-gradient( <-legacy-linear-gradient-arguments> )",
      "-legacy-linear-gradient-arguments": "[<angle>|<side-or-corner>]? , <color-stop-list>",
      "-legacy-radial-gradient": "-moz-radial-gradient( <-legacy-radial-gradient-arguments> )|-webkit-radial-gradient( <-legacy-radial-gradient-arguments> )|-o-radial-gradient( <-legacy-radial-gradient-arguments> )",
      "-legacy-repeating-radial-gradient": "-moz-repeating-radial-gradient( <-legacy-radial-gradient-arguments> )|-webkit-repeating-radial-gradient( <-legacy-radial-gradient-arguments> )|-o-repeating-radial-gradient( <-legacy-radial-gradient-arguments> )",
      "-legacy-radial-gradient-arguments": "[<position> ,]? [[[<-legacy-radial-gradient-shape>||<-legacy-radial-gradient-size>]|[<length>|<percentage>]{2}] ,]? <color-stop-list>",
      "-legacy-radial-gradient-size": "closest-side|closest-corner|farthest-side|farthest-corner|contain|cover",
      "-legacy-radial-gradient-shape": "circle|ellipse",
      "-non-standard-font": "-apple-system-body|-apple-system-headline|-apple-system-subheadline|-apple-system-caption1|-apple-system-caption2|-apple-system-footnote|-apple-system-short-body|-apple-system-short-headline|-apple-system-short-subheadline|-apple-system-short-caption1|-apple-system-short-footnote|-apple-system-tall-body",
      "-non-standard-color": "-moz-ButtonDefault|-moz-ButtonHoverFace|-moz-ButtonHoverText|-moz-CellHighlight|-moz-CellHighlightText|-moz-Combobox|-moz-ComboboxText|-moz-Dialog|-moz-DialogText|-moz-dragtargetzone|-moz-EvenTreeRow|-moz-Field|-moz-FieldText|-moz-html-CellHighlight|-moz-html-CellHighlightText|-moz-mac-accentdarkestshadow|-moz-mac-accentdarkshadow|-moz-mac-accentface|-moz-mac-accentlightesthighlight|-moz-mac-accentlightshadow|-moz-mac-accentregularhighlight|-moz-mac-accentregularshadow|-moz-mac-chrome-active|-moz-mac-chrome-inactive|-moz-mac-focusring|-moz-mac-menuselect|-moz-mac-menushadow|-moz-mac-menutextselect|-moz-MenuHover|-moz-MenuHoverText|-moz-MenuBarText|-moz-MenuBarHoverText|-moz-nativehyperlinktext|-moz-OddTreeRow|-moz-win-communicationstext|-moz-win-mediatext|-moz-activehyperlinktext|-moz-default-background-color|-moz-default-color|-moz-hyperlinktext|-moz-visitedhyperlinktext|-webkit-activelink|-webkit-focus-ring-color|-webkit-link|-webkit-text",
      "-non-standard-image-rendering": "optimize-contrast|-moz-crisp-edges|-o-crisp-edges|-webkit-optimize-contrast",
      "-non-standard-overflow": "-moz-scrollbars-none|-moz-scrollbars-horizontal|-moz-scrollbars-vertical|-moz-hidden-unscrollable",
      "-non-standard-width": "fill-available|min-intrinsic|intrinsic|-moz-available|-moz-fit-content|-moz-min-content|-moz-max-content|-webkit-min-content|-webkit-max-content",
      "-webkit-gradient()": "-webkit-gradient( <-webkit-gradient-type> , <-webkit-gradient-point> [, <-webkit-gradient-point>|, <-webkit-gradient-radius> , <-webkit-gradient-point>] [, <-webkit-gradient-radius>]? [, <-webkit-gradient-color-stop>]* )",
      "-webkit-gradient-color-stop": "from( <color> )|color-stop( [<number-zero-one>|<percentage>] , <color> )|to( <color> )",
      "-webkit-gradient-point": "[left|center|right|<length-percentage>] [top|center|bottom|<length-percentage>]",
      "-webkit-gradient-radius": "<length>|<percentage>",
      "-webkit-gradient-type": "linear|radial",
      "-webkit-mask-box-repeat": "repeat|stretch|round",
      "-webkit-mask-clip-style": "border|border-box|padding|padding-box|content|content-box|text",
      "-ms-filter-function-list": "<-ms-filter-function>+",
      "-ms-filter-function": "<-ms-filter-function-progid>|<-ms-filter-function-legacy>",
      "-ms-filter-function-progid": "'progid:' [<ident-token> '.']* [<ident-token>|<function-token> <any-value>? )]",
      "-ms-filter-function-legacy": "<ident-token>|<function-token> <any-value>? )",
      "-ms-filter": "<string>",
      "age": "child|young|old",
      "attr-name": "<wq-name>",
      "attr-fallback": "<any-value>",
      "bg-clip": "<box>|border|text",
      "bottom": "<length>|auto",
      "generic-voice": "[<age>? <gender> <integer>?]",
      "gender": "male|female|neutral",
      "left": "<length>|auto",
      "mask-image": "<mask-reference>#",
      "paint": "none|<color>|<url> [none|<color>]?|context-fill|context-stroke",
      "right": "<length>|auto",
      "scroll-timeline-axis": "block|inline|vertical|horizontal",
      "scroll-timeline-name": "none|<custom-ident>",
      "single-animation-composition": "replace|add|accumulate",
      "svg-length": "<percentage>|<length>|<number>",
      "svg-writing-mode": "lr-tb|rl-tb|tb-rl|lr|rl|tb",
      "top": "<length>|auto",
      "x": "<number>",
      "y": "<number>",
      "declaration": "<ident-token> : <declaration-value>? ['!' important]?",
      "declaration-list": "[<declaration>? ';']* <declaration>?",
      "url": "url( <string> <url-modifier>* )|<url-token>",
      "url-modifier": "<ident>|<function-token> <any-value> )",
      "number-zero-one": "<number [0,1]>",
      "number-one-or-greater": "<number [1,∞]>",
      "-non-standard-display": "-ms-inline-flexbox|-ms-grid|-ms-inline-grid|-webkit-flex|-webkit-inline-flex|-webkit-box|-webkit-inline-box|-moz-inline-stack|-moz-box|-moz-inline-box"
    },
    "properties": {
      "--*": "<declaration-value>",
      "-ms-accelerator": "false|true",
      "-ms-block-progression": "tb|rl|bt|lr",
      "-ms-content-zoom-chaining": "none|chained",
      "-ms-content-zooming": "none|zoom",
      "-ms-content-zoom-limit": "<'-ms-content-zoom-limit-min'> <'-ms-content-zoom-limit-max'>",
      "-ms-content-zoom-limit-max": "<percentage>",
      "-ms-content-zoom-limit-min": "<percentage>",
      "-ms-content-zoom-snap": "<'-ms-content-zoom-snap-type'>||<'-ms-content-zoom-snap-points'>",
      "-ms-content-zoom-snap-points": "snapInterval( <percentage> , <percentage> )|snapList( <percentage># )",
      "-ms-content-zoom-snap-type": "none|proximity|mandatory",
      "-ms-filter": "<string>",
      "-ms-flow-from": "[none|<custom-ident>]#",
      "-ms-flow-into": "[none|<custom-ident>]#",
      "-ms-grid-columns": "none|<track-list>|<auto-track-list>",
      "-ms-grid-rows": "none|<track-list>|<auto-track-list>",
      "-ms-high-contrast-adjust": "auto|none",
      "-ms-hyphenate-limit-chars": "auto|<integer>{1,3}",
      "-ms-hyphenate-limit-lines": "no-limit|<integer>",
      "-ms-hyphenate-limit-zone": "<percentage>|<length>",
      "-ms-ime-align": "auto|after",
      "-ms-overflow-style": "auto|none|scrollbar|-ms-autohiding-scrollbar",
      "-ms-scrollbar-3dlight-color": "<color>",
      "-ms-scrollbar-arrow-color": "<color>",
      "-ms-scrollbar-base-color": "<color>",
      "-ms-scrollbar-darkshadow-color": "<color>",
      "-ms-scrollbar-face-color": "<color>",
      "-ms-scrollbar-highlight-color": "<color>",
      "-ms-scrollbar-shadow-color": "<color>",
      "-ms-scrollbar-track-color": "<color>",
      "-ms-scroll-chaining": "chained|none",
      "-ms-scroll-limit": "<'-ms-scroll-limit-x-min'> <'-ms-scroll-limit-y-min'> <'-ms-scroll-limit-x-max'> <'-ms-scroll-limit-y-max'>",
      "-ms-scroll-limit-x-max": "auto|<length>",
      "-ms-scroll-limit-x-min": "<length>",
      "-ms-scroll-limit-y-max": "auto|<length>",
      "-ms-scroll-limit-y-min": "<length>",
      "-ms-scroll-rails": "none|railed",
      "-ms-scroll-snap-points-x": "snapInterval( <length-percentage> , <length-percentage> )|snapList( <length-percentage># )",
      "-ms-scroll-snap-points-y": "snapInterval( <length-percentage> , <length-percentage> )|snapList( <length-percentage># )",
      "-ms-scroll-snap-type": "none|proximity|mandatory",
      "-ms-scroll-snap-x": "<'-ms-scroll-snap-type'> <'-ms-scroll-snap-points-x'>",
      "-ms-scroll-snap-y": "<'-ms-scroll-snap-type'> <'-ms-scroll-snap-points-y'>",
      "-ms-scroll-translation": "none|vertical-to-horizontal",
      "-ms-text-autospace": "none|ideograph-alpha|ideograph-numeric|ideograph-parenthesis|ideograph-space",
      "-ms-touch-select": "grippers|none",
      "-ms-user-select": "none|element|text",
      "-ms-wrap-flow": "auto|both|start|end|maximum|clear",
      "-ms-wrap-margin": "<length>",
      "-ms-wrap-through": "wrap|none",
      "-moz-appearance": "none|button|button-arrow-down|button-arrow-next|button-arrow-previous|button-arrow-up|button-bevel|button-focus|caret|checkbox|checkbox-container|checkbox-label|checkmenuitem|dualbutton|groupbox|listbox|listitem|menuarrow|menubar|menucheckbox|menuimage|menuitem|menuitemtext|menulist|menulist-button|menulist-text|menulist-textfield|menupopup|menuradio|menuseparator|meterbar|meterchunk|progressbar|progressbar-vertical|progresschunk|progresschunk-vertical|radio|radio-container|radio-label|radiomenuitem|range|range-thumb|resizer|resizerpanel|scale-horizontal|scalethumbend|scalethumb-horizontal|scalethumbstart|scalethumbtick|scalethumb-vertical|scale-vertical|scrollbarbutton-down|scrollbarbutton-left|scrollbarbutton-right|scrollbarbutton-up|scrollbarthumb-horizontal|scrollbarthumb-vertical|scrollbartrack-horizontal|scrollbartrack-vertical|searchfield|separator|sheet|spinner|spinner-downbutton|spinner-textfield|spinner-upbutton|splitter|statusbar|statusbarpanel|tab|tabpanel|tabpanels|tab-scroll-arrow-back|tab-scroll-arrow-forward|textfield|textfield-multiline|toolbar|toolbarbutton|toolbarbutton-dropdown|toolbargripper|toolbox|tooltip|treeheader|treeheadercell|treeheadersortarrow|treeitem|treeline|treetwisty|treetwistyopen|treeview|-moz-mac-unified-toolbar|-moz-win-borderless-glass|-moz-win-browsertabbar-toolbox|-moz-win-communicationstext|-moz-win-communications-toolbox|-moz-win-exclude-glass|-moz-win-glass|-moz-win-mediatext|-moz-win-media-toolbox|-moz-window-button-box|-moz-window-button-box-maximized|-moz-window-button-close|-moz-window-button-maximize|-moz-window-button-minimize|-moz-window-button-restore|-moz-window-frame-bottom|-moz-window-frame-left|-moz-window-frame-right|-moz-window-titlebar|-moz-window-titlebar-maximized",
      "-moz-binding": "<url>|none",
      "-moz-border-bottom-colors": "<color>+|none",
      "-moz-border-left-colors": "<color>+|none",
      "-moz-border-right-colors": "<color>+|none",
      "-moz-border-top-colors": "<color>+|none",
      "-moz-context-properties": "none|[fill|fill-opacity|stroke|stroke-opacity]#",
      "-moz-float-edge": "border-box|content-box|margin-box|padding-box",
      "-moz-force-broken-image-icon": "0|1",
      "-moz-image-region": "<shape>|auto",
      "-moz-orient": "inline|block|horizontal|vertical",
      "-moz-outline-radius": "<outline-radius>{1,4} [/ <outline-radius>{1,4}]?",
      "-moz-outline-radius-bottomleft": "<outline-radius>",
      "-moz-outline-radius-bottomright": "<outline-radius>",
      "-moz-outline-radius-topleft": "<outline-radius>",
      "-moz-outline-radius-topright": "<outline-radius>",
      "-moz-stack-sizing": "ignore|stretch-to-fit",
      "-moz-text-blink": "none|blink",
      "-moz-user-focus": "ignore|normal|select-after|select-before|select-menu|select-same|select-all|none",
      "-moz-user-input": "auto|none|enabled|disabled",
      "-moz-user-modify": "read-only|read-write|write-only",
      "-moz-window-dragging": "drag|no-drag",
      "-moz-window-shadow": "default|menu|tooltip|sheet|none",
      "-webkit-appearance": "none|button|button-bevel|caps-lock-indicator|caret|checkbox|default-button|inner-spin-button|listbox|listitem|media-controls-background|media-controls-fullscreen-background|media-current-time-display|media-enter-fullscreen-button|media-exit-fullscreen-button|media-fullscreen-button|media-mute-button|media-overlay-play-button|media-play-button|media-seek-back-button|media-seek-forward-button|media-slider|media-sliderthumb|media-time-remaining-display|media-toggle-closed-captions-button|media-volume-slider|media-volume-slider-container|media-volume-sliderthumb|menulist|menulist-button|menulist-text|menulist-textfield|meter|progress-bar|progress-bar-value|push-button|radio|scrollbarbutton-down|scrollbarbutton-left|scrollbarbutton-right|scrollbarbutton-up|scrollbargripper-horizontal|scrollbargripper-vertical|scrollbarthumb-horizontal|scrollbarthumb-vertical|scrollbartrack-horizontal|scrollbartrack-vertical|searchfield|searchfield-cancel-button|searchfield-decoration|searchfield-results-button|searchfield-results-decoration|slider-horizontal|slider-vertical|sliderthumb-horizontal|sliderthumb-vertical|square-button|textarea|textfield|-apple-pay-button",
      "-webkit-border-before": "<'border-width'>||<'border-style'>||<color>",
      "-webkit-border-before-color": "<color>",
      "-webkit-border-before-style": "<'border-style'>",
      "-webkit-border-before-width": "<'border-width'>",
      "-webkit-box-reflect": "[above|below|right|left]? <length>? <image>?",
      "-webkit-line-clamp": "none|<integer>",
      "-webkit-mask": "[<mask-reference>||<position> [/ <bg-size>]?||<repeat-style>||[<box>|border|padding|content|text]||[<box>|border|padding|content]]#",
      "-webkit-mask-attachment": "<attachment>#",
      "-webkit-mask-clip": "[<box>|border|padding|content|text]#",
      "-webkit-mask-composite": "<composite-style>#",
      "-webkit-mask-image": "<mask-reference>#",
      "-webkit-mask-origin": "[<box>|border|padding|content]#",
      "-webkit-mask-position": "<position>#",
      "-webkit-mask-position-x": "[<length-percentage>|left|center|right]#",
      "-webkit-mask-position-y": "[<length-percentage>|top|center|bottom]#",
      "-webkit-mask-repeat": "<repeat-style>#",
      "-webkit-mask-repeat-x": "repeat|no-repeat|space|round",
      "-webkit-mask-repeat-y": "repeat|no-repeat|space|round",
      "-webkit-mask-size": "<bg-size>#",
      "-webkit-overflow-scrolling": "auto|touch",
      "-webkit-tap-highlight-color": "<color>",
      "-webkit-text-fill-color": "<color>",
      "-webkit-text-stroke": "<length>||<color>",
      "-webkit-text-stroke-color": "<color>",
      "-webkit-text-stroke-width": "<length>",
      "-webkit-touch-callout": "default|none",
      "-webkit-user-modify": "read-only|read-write|read-write-plaintext-only",
      "accent-color": "auto|<color>",
      "align-content": "normal|<baseline-position>|<content-distribution>|<overflow-position>? <content-position>",
      "align-items": "normal|stretch|<baseline-position>|[<overflow-position>? <self-position>]",
      "align-self": "auto|normal|stretch|<baseline-position>|<overflow-position>? <self-position>",
      "align-tracks": "[normal|<baseline-position>|<content-distribution>|<overflow-position>? <content-position>]#",
      "all": "initial|inherit|unset|revert|revert-layer",
      "animation": "<single-animation>#",
      "animation-composition": "<single-animation-composition>#",
      "animation-delay": "<time>#",
      "animation-direction": "<single-animation-direction>#",
      "animation-duration": "<time>#",
      "animation-fill-mode": "<single-animation-fill-mode>#",
      "animation-iteration-count": "<single-animation-iteration-count>#",
      "animation-name": "[none|<keyframes-name>]#",
      "animation-play-state": "<single-animation-play-state>#",
      "animation-timing-function": "<easing-function>#",
      "animation-timeline": "<single-animation-timeline>#",
      "appearance": "none|auto|textfield|menulist-button|<compat-auto>",
      "aspect-ratio": "auto|<ratio>",
      "azimuth": "<angle>|[[left-side|far-left|left|center-left|center|center-right|right|far-right|right-side]||behind]|leftwards|rightwards",
      "backdrop-filter": "none|<filter-function-list>",
      "backface-visibility": "visible|hidden",
      "background": "[<bg-layer> ,]* <final-bg-layer>",
      "background-attachment": "<attachment>#",
      "background-blend-mode": "<blend-mode>#",
      "background-clip": "<bg-clip>#",
      "background-color": "<color>",
      "background-image": "<bg-image>#",
      "background-origin": "<box>#",
      "background-position": "<bg-position>#",
      "background-position-x": "[center|[[left|right|x-start|x-end]? <length-percentage>?]!]#",
      "background-position-y": "[center|[[top|bottom|y-start|y-end]? <length-percentage>?]!]#",
      "background-repeat": "<repeat-style>#",
      "background-size": "<bg-size>#",
      "block-overflow": "clip|ellipsis|<string>",
      "block-size": "<'width'>",
      "border": "<line-width>||<line-style>||<color>",
      "border-block": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-block-color": "<'border-top-color'>{1,2}",
      "border-block-style": "<'border-top-style'>",
      "border-block-width": "<'border-top-width'>",
      "border-block-end": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-block-end-color": "<'border-top-color'>",
      "border-block-end-style": "<'border-top-style'>",
      "border-block-end-width": "<'border-top-width'>",
      "border-block-start": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-block-start-color": "<'border-top-color'>",
      "border-block-start-style": "<'border-top-style'>",
      "border-block-start-width": "<'border-top-width'>",
      "border-bottom": "<line-width>||<line-style>||<color>",
      "border-bottom-color": "<'border-top-color'>",
      "border-bottom-left-radius": "<length-percentage>{1,2}",
      "border-bottom-right-radius": "<length-percentage>{1,2}",
      "border-bottom-style": "<line-style>",
      "border-bottom-width": "<line-width>",
      "border-collapse": "collapse|separate",
      "border-color": "<color>{1,4}",
      "border-end-end-radius": "<length-percentage>{1,2}",
      "border-end-start-radius": "<length-percentage>{1,2}",
      "border-image": "<'border-image-source'>||<'border-image-slice'> [/ <'border-image-width'>|/ <'border-image-width'>? / <'border-image-outset'>]?||<'border-image-repeat'>",
      "border-image-outset": "[<length>|<number>]{1,4}",
      "border-image-repeat": "[stretch|repeat|round|space]{1,2}",
      "border-image-slice": "<number-percentage>{1,4}&&fill?",
      "border-image-source": "none|<image>",
      "border-image-width": "[<length-percentage>|<number>|auto]{1,4}",
      "border-inline": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-inline-end": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-inline-color": "<'border-top-color'>{1,2}",
      "border-inline-style": "<'border-top-style'>",
      "border-inline-width": "<'border-top-width'>",
      "border-inline-end-color": "<'border-top-color'>",
      "border-inline-end-style": "<'border-top-style'>",
      "border-inline-end-width": "<'border-top-width'>",
      "border-inline-start": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-inline-start-color": "<'border-top-color'>",
      "border-inline-start-style": "<'border-top-style'>",
      "border-inline-start-width": "<'border-top-width'>",
      "border-left": "<line-width>||<line-style>||<color>",
      "border-left-color": "<color>",
      "border-left-style": "<line-style>",
      "border-left-width": "<line-width>",
      "border-radius": "<length-percentage>{1,4} [/ <length-percentage>{1,4}]?",
      "border-right": "<line-width>||<line-style>||<color>",
      "border-right-color": "<color>",
      "border-right-style": "<line-style>",
      "border-right-width": "<line-width>",
      "border-spacing": "<length> <length>?",
      "border-start-end-radius": "<length-percentage>{1,2}",
      "border-start-start-radius": "<length-percentage>{1,2}",
      "border-style": "<line-style>{1,4}",
      "border-top": "<line-width>||<line-style>||<color>",
      "border-top-color": "<color>",
      "border-top-left-radius": "<length-percentage>{1,2}",
      "border-top-right-radius": "<length-percentage>{1,2}",
      "border-top-style": "<line-style>",
      "border-top-width": "<line-width>",
      "border-width": "<line-width>{1,4}",
      "bottom": "<length>|<percentage>|auto",
      "box-align": "start|center|end|baseline|stretch",
      "box-decoration-break": "slice|clone",
      "box-direction": "normal|reverse|inherit",
      "box-flex": "<number>",
      "box-flex-group": "<integer>",
      "box-lines": "single|multiple",
      "box-ordinal-group": "<integer>",
      "box-orient": "horizontal|vertical|inline-axis|block-axis|inherit",
      "box-pack": "start|center|end|justify",
      "box-shadow": "none|<shadow>#",
      "box-sizing": "content-box|border-box",
      "break-after": "auto|avoid|always|all|avoid-page|page|left|right|recto|verso|avoid-column|column|avoid-region|region",
      "break-before": "auto|avoid|always|all|avoid-page|page|left|right|recto|verso|avoid-column|column|avoid-region|region",
      "break-inside": "auto|avoid|avoid-page|avoid-column|avoid-region",
      "caption-side": "top|bottom|block-start|block-end|inline-start|inline-end",
      "caret": "<'caret-color'>||<'caret-shape'>",
      "caret-color": "auto|<color>",
      "caret-shape": "auto|bar|block|underscore",
      "clear": "none|left|right|both|inline-start|inline-end",
      "clip": "<shape>|auto",
      "clip-path": "<clip-source>|[<basic-shape>||<geometry-box>]|none",
      "color": "<color>",
      "print-color-adjust": "economy|exact",
      "color-scheme": "normal|[light|dark|<custom-ident>]+&&only?",
      "column-count": "<integer>|auto",
      "column-fill": "auto|balance|balance-all",
      "column-gap": "normal|<length-percentage>",
      "column-rule": "<'column-rule-width'>||<'column-rule-style'>||<'column-rule-color'>",
      "column-rule-color": "<color>",
      "column-rule-style": "<'border-style'>",
      "column-rule-width": "<'border-width'>",
      "column-span": "none|all",
      "column-width": "<length>|auto",
      "columns": "<'column-width'>||<'column-count'>",
      "contain": "none|strict|content|[[size||inline-size]||layout||style||paint]",
      "contain-intrinsic-size": "[none|<length>|auto <length>]{1,2}",
      "contain-intrinsic-block-size": "none|<length>|auto <length>",
      "contain-intrinsic-height": "none|<length>|auto <length>",
      "contain-intrinsic-inline-size": "none|<length>|auto <length>",
      "contain-intrinsic-width": "none|<length>|auto <length>",
      "content": "normal|none|[<content-replacement>|<content-list>] [/ [<string>|<counter>]+]?",
      "content-visibility": "visible|auto|hidden",
      "counter-increment": "[<counter-name> <integer>?]+|none",
      "counter-reset": "[<counter-name> <integer>?|<reversed-counter-name> <integer>?]+|none",
      "counter-set": "[<counter-name> <integer>?]+|none",
      "cursor": "[[<url> [<x> <y>]? ,]* [auto|default|none|context-menu|help|pointer|progress|wait|cell|crosshair|text|vertical-text|alias|copy|move|no-drop|not-allowed|e-resize|n-resize|ne-resize|nw-resize|s-resize|se-resize|sw-resize|w-resize|ew-resize|ns-resize|nesw-resize|nwse-resize|col-resize|row-resize|all-scroll|zoom-in|zoom-out|grab|grabbing|hand|-webkit-grab|-webkit-grabbing|-webkit-zoom-in|-webkit-zoom-out|-moz-grab|-moz-grabbing|-moz-zoom-in|-moz-zoom-out]]",
      "direction": "ltr|rtl",
      "display": "[<display-outside>||<display-inside>]|<display-listitem>|<display-internal>|<display-box>|<display-legacy>|<-non-standard-display>",
      "empty-cells": "show|hide",
      "filter": "none|<filter-function-list>|<-ms-filter-function-list>",
      "flex": "none|[<'flex-grow'> <'flex-shrink'>?||<'flex-basis'>]",
      "flex-basis": "content|<'width'>",
      "flex-direction": "row|row-reverse|column|column-reverse",
      "flex-flow": "<'flex-direction'>||<'flex-wrap'>",
      "flex-grow": "<number>",
      "flex-shrink": "<number>",
      "flex-wrap": "nowrap|wrap|wrap-reverse",
      "float": "left|right|none|inline-start|inline-end",
      "font": "[[<'font-style'>||<font-variant-css21>||<'font-weight'>||<'font-stretch'>]? <'font-size'> [/ <'line-height'>]? <'font-family'>]|caption|icon|menu|message-box|small-caption|status-bar",
      "font-family": "[<family-name>|<generic-family>]#",
      "font-feature-settings": "normal|<feature-tag-value>#",
      "font-kerning": "auto|normal|none",
      "font-language-override": "normal|<string>",
      "font-optical-sizing": "auto|none",
      "font-variation-settings": "normal|[<string> <number>]#",
      "font-size": "<absolute-size>|<relative-size>|<length-percentage>",
      "font-size-adjust": "none|[ex-height|cap-height|ch-width|ic-width|ic-height]? [from-font|<number>]",
      "font-smooth": "auto|never|always|<absolute-size>|<length>",
      "font-stretch": "<font-stretch-absolute>",
      "font-style": "normal|italic|oblique <angle>?",
      "font-synthesis": "none|[weight||style||small-caps]",
      "font-variant": "normal|none|[<common-lig-values>||<discretionary-lig-values>||<historical-lig-values>||<contextual-alt-values>||stylistic( <feature-value-name> )||historical-forms||styleset( <feature-value-name># )||character-variant( <feature-value-name># )||swash( <feature-value-name> )||ornaments( <feature-value-name> )||annotation( <feature-value-name> )||[small-caps|all-small-caps|petite-caps|all-petite-caps|unicase|titling-caps]||<numeric-figure-values>||<numeric-spacing-values>||<numeric-fraction-values>||ordinal||slashed-zero||<east-asian-variant-values>||<east-asian-width-values>||ruby]",
      "font-variant-alternates": "normal|[stylistic( <feature-value-name> )||historical-forms||styleset( <feature-value-name># )||character-variant( <feature-value-name># )||swash( <feature-value-name> )||ornaments( <feature-value-name> )||annotation( <feature-value-name> )]",
      "font-variant-caps": "normal|small-caps|all-small-caps|petite-caps|all-petite-caps|unicase|titling-caps",
      "font-variant-east-asian": "normal|[<east-asian-variant-values>||<east-asian-width-values>||ruby]",
      "font-variant-ligatures": "normal|none|[<common-lig-values>||<discretionary-lig-values>||<historical-lig-values>||<contextual-alt-values>]",
      "font-variant-numeric": "normal|[<numeric-figure-values>||<numeric-spacing-values>||<numeric-fraction-values>||ordinal||slashed-zero]",
      "font-variant-position": "normal|sub|super",
      "font-weight": "<font-weight-absolute>|bolder|lighter",
      "forced-color-adjust": "auto|none",
      "gap": "<'row-gap'> <'column-gap'>?",
      "grid": "<'grid-template'>|<'grid-template-rows'> / [auto-flow&&dense?] <'grid-auto-columns'>?|[auto-flow&&dense?] <'grid-auto-rows'>? / <'grid-template-columns'>",
      "grid-area": "<grid-line> [/ <grid-line>]{0,3}",
      "grid-auto-columns": "<track-size>+",
      "grid-auto-flow": "[row|column]||dense",
      "grid-auto-rows": "<track-size>+",
      "grid-column": "<grid-line> [/ <grid-line>]?",
      "grid-column-end": "<grid-line>",
      "grid-column-gap": "<length-percentage>",
      "grid-column-start": "<grid-line>",
      "grid-gap": "<'grid-row-gap'> <'grid-column-gap'>?",
      "grid-row": "<grid-line> [/ <grid-line>]?",
      "grid-row-end": "<grid-line>",
      "grid-row-gap": "<length-percentage>",
      "grid-row-start": "<grid-line>",
      "grid-template": "none|[<'grid-template-rows'> / <'grid-template-columns'>]|[<line-names>? <string> <track-size>? <line-names>?]+ [/ <explicit-track-list>]?",
      "grid-template-areas": "none|<string>+",
      "grid-template-columns": "none|<track-list>|<auto-track-list>|subgrid <line-name-list>?",
      "grid-template-rows": "none|<track-list>|<auto-track-list>|subgrid <line-name-list>?",
      "hanging-punctuation": "none|[first||[force-end|allow-end]||last]",
      "height": "auto|<length>|<percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )",
      "hyphenate-character": "auto|<string>",
      "hyphens": "none|manual|auto",
      "image-orientation": "from-image|<angle>|[<angle>? flip]",
      "image-rendering": "auto|crisp-edges|pixelated|optimizeSpeed|optimizeQuality|<-non-standard-image-rendering>",
      "image-resolution": "[from-image||<resolution>]&&snap?",
      "ime-mode": "auto|normal|active|inactive|disabled",
      "initial-letter": "normal|[<number> <integer>?]",
      "initial-letter-align": "[auto|alphabetic|hanging|ideographic]",
      "inline-size": "<'width'>",
      "input-security": "auto|none",
      "inset": "<'top'>{1,4}",
      "inset-block": "<'top'>{1,2}",
      "inset-block-end": "<'top'>",
      "inset-block-start": "<'top'>",
      "inset-inline": "<'top'>{1,2}",
      "inset-inline-end": "<'top'>",
      "inset-inline-start": "<'top'>",
      "isolation": "auto|isolate",
      "justify-content": "normal|<content-distribution>|<overflow-position>? [<content-position>|left|right]",
      "justify-items": "normal|stretch|<baseline-position>|<overflow-position>? [<self-position>|left|right]|legacy|legacy&&[left|right|center]",
      "justify-self": "auto|normal|stretch|<baseline-position>|<overflow-position>? [<self-position>|left|right]",
      "justify-tracks": "[normal|<content-distribution>|<overflow-position>? [<content-position>|left|right]]#",
      "left": "<length>|<percentage>|auto",
      "letter-spacing": "normal|<length-percentage>",
      "line-break": "auto|loose|normal|strict|anywhere",
      "line-clamp": "none|<integer>",
      "line-height": "normal|<number>|<length>|<percentage>",
      "line-height-step": "<length>",
      "list-style": "<'list-style-type'>||<'list-style-position'>||<'list-style-image'>",
      "list-style-image": "<image>|none",
      "list-style-position": "inside|outside",
      "list-style-type": "<counter-style>|<string>|none",
      "margin": "[<length>|<percentage>|auto]{1,4}",
      "margin-block": "<'margin-left'>{1,2}",
      "margin-block-end": "<'margin-left'>",
      "margin-block-start": "<'margin-left'>",
      "margin-bottom": "<length>|<percentage>|auto",
      "margin-inline": "<'margin-left'>{1,2}",
      "margin-inline-end": "<'margin-left'>",
      "margin-inline-start": "<'margin-left'>",
      "margin-left": "<length>|<percentage>|auto",
      "margin-right": "<length>|<percentage>|auto",
      "margin-top": "<length>|<percentage>|auto",
      "margin-trim": "none|in-flow|all",
      "mask": "<mask-layer>#",
      "mask-border": "<'mask-border-source'>||<'mask-border-slice'> [/ <'mask-border-width'>? [/ <'mask-border-outset'>]?]?||<'mask-border-repeat'>||<'mask-border-mode'>",
      "mask-border-mode": "luminance|alpha",
      "mask-border-outset": "[<length>|<number>]{1,4}",
      "mask-border-repeat": "[stretch|repeat|round|space]{1,2}",
      "mask-border-slice": "<number-percentage>{1,4} fill?",
      "mask-border-source": "none|<image>",
      "mask-border-width": "[<length-percentage>|<number>|auto]{1,4}",
      "mask-clip": "[<geometry-box>|no-clip]#",
      "mask-composite": "<compositing-operator>#",
      "mask-image": "<mask-reference>#",
      "mask-mode": "<masking-mode>#",
      "mask-origin": "<geometry-box>#",
      "mask-position": "<position>#",
      "mask-repeat": "<repeat-style>#",
      "mask-size": "<bg-size>#",
      "mask-type": "luminance|alpha",
      "masonry-auto-flow": "[pack|next]||[definite-first|ordered]",
      "math-depth": "auto-add|add( <integer> )|<integer>",
      "math-shift": "normal|compact",
      "math-style": "normal|compact",
      "max-block-size": "<'max-width'>",
      "max-height": "none|<length-percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )",
      "max-inline-size": "<'max-width'>",
      "max-lines": "none|<integer>",
      "max-width": "none|<length-percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )|<-non-standard-width>",
      "min-block-size": "<'min-width'>",
      "min-height": "auto|<length>|<percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )",
      "min-inline-size": "<'min-width'>",
      "min-width": "auto|<length>|<percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )|<-non-standard-width>",
      "mix-blend-mode": "<blend-mode>|plus-lighter",
      "object-fit": "fill|contain|cover|none|scale-down",
      "object-position": "<position>",
      "offset": "[<'offset-position'>? [<'offset-path'> [<'offset-distance'>||<'offset-rotate'>]?]?]! [/ <'offset-anchor'>]?",
      "offset-anchor": "auto|<position>",
      "offset-distance": "<length-percentage>",
      "offset-path": "none|ray( [<angle>&&<size>&&contain?] )|<path()>|<url>|[<basic-shape>||<geometry-box>]",
      "offset-position": "auto|<position>",
      "offset-rotate": "[auto|reverse]||<angle>",
      "opacity": "<alpha-value>",
      "order": "<integer>",
      "orphans": "<integer>",
      "outline": "[<'outline-color'>||<'outline-style'>||<'outline-width'>]",
      "outline-color": "<color>|invert",
      "outline-offset": "<length>",
      "outline-style": "auto|<'border-style'>",
      "outline-width": "<line-width>",
      "overflow": "[visible|hidden|clip|scroll|auto]{1,2}|<-non-standard-overflow>",
      "overflow-anchor": "auto|none",
      "overflow-block": "visible|hidden|clip|scroll|auto",
      "overflow-clip-box": "padding-box|content-box",
      "overflow-clip-margin": "<visual-box>||<length [0,∞]>",
      "overflow-inline": "visible|hidden|clip|scroll|auto",
      "overflow-wrap": "normal|break-word|anywhere",
      "overflow-x": "visible|hidden|clip|scroll|auto",
      "overflow-y": "visible|hidden|clip|scroll|auto",
      "overscroll-behavior": "[contain|none|auto]{1,2}",
      "overscroll-behavior-block": "contain|none|auto",
      "overscroll-behavior-inline": "contain|none|auto",
      "overscroll-behavior-x": "contain|none|auto",
      "overscroll-behavior-y": "contain|none|auto",
      "padding": "[<length>|<percentage>]{1,4}",
      "padding-block": "<'padding-left'>{1,2}",
      "padding-block-end": "<'padding-left'>",
      "padding-block-start": "<'padding-left'>",
      "padding-bottom": "<length>|<percentage>",
      "padding-inline": "<'padding-left'>{1,2}",
      "padding-inline-end": "<'padding-left'>",
      "padding-inline-start": "<'padding-left'>",
      "padding-left": "<length>|<percentage>",
      "padding-right": "<length>|<percentage>",
      "padding-top": "<length>|<percentage>",
      "page-break-after": "auto|always|avoid|left|right|recto|verso",
      "page-break-before": "auto|always|avoid|left|right|recto|verso",
      "page-break-inside": "auto|avoid",
      "paint-order": "normal|[fill||stroke||markers]",
      "perspective": "none|<length>",
      "perspective-origin": "<position>",
      "place-content": "<'align-content'> <'justify-content'>?",
      "place-items": "<'align-items'> <'justify-items'>?",
      "place-self": "<'align-self'> <'justify-self'>?",
      "pointer-events": "auto|none|visiblePainted|visibleFill|visibleStroke|visible|painted|fill|stroke|all|inherit",
      "position": "static|relative|absolute|sticky|fixed|-webkit-sticky",
      "quotes": "none|auto|[<string> <string>]+",
      "resize": "none|both|horizontal|vertical|block|inline",
      "right": "<length>|<percentage>|auto",
      "rotate": "none|<angle>|[x|y|z|<number>{3}]&&<angle>",
      "row-gap": "normal|<length-percentage>",
      "ruby-align": "start|center|space-between|space-around",
      "ruby-merge": "separate|collapse|auto",
      "ruby-position": "[alternate||[over|under]]|inter-character",
      "scale": "none|<number>{1,3}",
      "scrollbar-color": "auto|<color>{2}",
      "scrollbar-gutter": "auto|stable&&both-edges?",
      "scrollbar-width": "auto|thin|none",
      "scroll-behavior": "auto|smooth",
      "scroll-margin": "<length>{1,4}",
      "scroll-margin-block": "<length>{1,2}",
      "scroll-margin-block-start": "<length>",
      "scroll-margin-block-end": "<length>",
      "scroll-margin-bottom": "<length>",
      "scroll-margin-inline": "<length>{1,2}",
      "scroll-margin-inline-start": "<length>",
      "scroll-margin-inline-end": "<length>",
      "scroll-margin-left": "<length>",
      "scroll-margin-right": "<length>",
      "scroll-margin-top": "<length>",
      "scroll-padding": "[auto|<length-percentage>]{1,4}",
      "scroll-padding-block": "[auto|<length-percentage>]{1,2}",
      "scroll-padding-block-start": "auto|<length-percentage>",
      "scroll-padding-block-end": "auto|<length-percentage>",
      "scroll-padding-bottom": "auto|<length-percentage>",
      "scroll-padding-inline": "[auto|<length-percentage>]{1,2}",
      "scroll-padding-inline-start": "auto|<length-percentage>",
      "scroll-padding-inline-end": "auto|<length-percentage>",
      "scroll-padding-left": "auto|<length-percentage>",
      "scroll-padding-right": "auto|<length-percentage>",
      "scroll-padding-top": "auto|<length-percentage>",
      "scroll-snap-align": "[none|start|end|center]{1,2}",
      "scroll-snap-coordinate": "none|<position>#",
      "scroll-snap-destination": "<position>",
      "scroll-snap-points-x": "none|repeat( <length-percentage> )",
      "scroll-snap-points-y": "none|repeat( <length-percentage> )",
      "scroll-snap-stop": "normal|always",
      "scroll-snap-type": "none|[x|y|block|inline|both] [mandatory|proximity]?",
      "scroll-snap-type-x": "none|mandatory|proximity",
      "scroll-snap-type-y": "none|mandatory|proximity",
      "scroll-timeline": "<scroll-timeline-name>||<scroll-timeline-axis>",
      "scroll-timeline-axis": "block|inline|vertical|horizontal",
      "scroll-timeline-name": "none|<custom-ident>",
      "shape-image-threshold": "<alpha-value>",
      "shape-margin": "<length-percentage>",
      "shape-outside": "none|[<shape-box>||<basic-shape>]|<image>",
      "tab-size": "<integer>|<length>",
      "table-layout": "auto|fixed",
      "text-align": "start|end|left|right|center|justify|match-parent",
      "text-align-last": "auto|start|end|left|right|center|justify",
      "text-combine-upright": "none|all|[digits <integer>?]",
      "text-decoration": "<'text-decoration-line'>||<'text-decoration-style'>||<'text-decoration-color'>||<'text-decoration-thickness'>",
      "text-decoration-color": "<color>",
      "text-decoration-line": "none|[underline||overline||line-through||blink]|spelling-error|grammar-error",
      "text-decoration-skip": "none|[objects||[spaces|[leading-spaces||trailing-spaces]]||edges||box-decoration]",
      "text-decoration-skip-ink": "auto|all|none",
      "text-decoration-style": "solid|double|dotted|dashed|wavy",
      "text-decoration-thickness": "auto|from-font|<length>|<percentage>",
      "text-emphasis": "<'text-emphasis-style'>||<'text-emphasis-color'>",
      "text-emphasis-color": "<color>",
      "text-emphasis-position": "[over|under]&&[right|left]",
      "text-emphasis-style": "none|[[filled|open]||[dot|circle|double-circle|triangle|sesame]]|<string>",
      "text-indent": "<length-percentage>&&hanging?&&each-line?",
      "text-justify": "auto|inter-character|inter-word|none",
      "text-orientation": "mixed|upright|sideways",
      "text-overflow": "[clip|ellipsis|<string>]{1,2}",
      "text-rendering": "auto|optimizeSpeed|optimizeLegibility|geometricPrecision",
      "text-shadow": "none|<shadow-t>#",
      "text-size-adjust": "none|auto|<percentage>",
      "text-transform": "none|capitalize|uppercase|lowercase|full-width|full-size-kana",
      "text-underline-offset": "auto|<length>|<percentage>",
      "text-underline-position": "auto|from-font|[under||[left|right]]",
      "top": "<length>|<percentage>|auto",
      "touch-action": "auto|none|[[pan-x|pan-left|pan-right]||[pan-y|pan-up|pan-down]||pinch-zoom]|manipulation",
      "transform": "none|<transform-list>",
      "transform-box": "content-box|border-box|fill-box|stroke-box|view-box",
      "transform-origin": "[<length-percentage>|left|center|right|top|bottom]|[[<length-percentage>|left|center|right]&&[<length-percentage>|top|center|bottom]] <length>?",
      "transform-style": "flat|preserve-3d",
      "transition": "<single-transition>#",
      "transition-delay": "<time>#",
      "transition-duration": "<time>#",
      "transition-property": "none|<single-transition-property>#",
      "transition-timing-function": "<easing-function>#",
      "translate": "none|<length-percentage> [<length-percentage> <length>?]?",
      "unicode-bidi": "normal|embed|isolate|bidi-override|isolate-override|plaintext|-moz-isolate|-moz-isolate-override|-moz-plaintext|-webkit-isolate|-webkit-isolate-override|-webkit-plaintext",
      "user-select": "auto|text|none|contain|all",
      "vertical-align": "baseline|sub|super|text-top|text-bottom|middle|top|bottom|<percentage>|<length>",
      "visibility": "visible|hidden|collapse",
      "white-space": "normal|pre|nowrap|pre-wrap|pre-line|break-spaces",
      "widows": "<integer>",
      "width": "auto|<length>|<percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )|fill|stretch|intrinsic|-moz-max-content|-webkit-max-content|-moz-fit-content|-webkit-fit-content",
      "will-change": "auto|<animateable-feature>#",
      "word-break": "normal|break-all|keep-all|break-word",
      "word-spacing": "normal|<length>",
      "word-wrap": "normal|break-word",
      "writing-mode": "horizontal-tb|vertical-rl|vertical-lr|sideways-rl|sideways-lr|<svg-writing-mode>",
      "z-index": "auto|<integer>",
      "zoom": "normal|reset|<number>|<percentage>",
      "-moz-background-clip": "padding|border",
      "-moz-border-radius-bottomleft": "<'border-bottom-left-radius'>",
      "-moz-border-radius-bottomright": "<'border-bottom-right-radius'>",
      "-moz-border-radius-topleft": "<'border-top-left-radius'>",
      "-moz-border-radius-topright": "<'border-bottom-right-radius'>",
      "-moz-control-character-visibility": "visible|hidden",
      "-moz-osx-font-smoothing": "auto|grayscale",
      "-moz-user-select": "none|text|all|-moz-none",
      "-ms-flex-align": "start|end|center|baseline|stretch",
      "-ms-flex-item-align": "auto|start|end|center|baseline|stretch",
      "-ms-flex-line-pack": "start|end|center|justify|distribute|stretch",
      "-ms-flex-negative": "<'flex-shrink'>",
      "-ms-flex-pack": "start|end|center|justify|distribute",
      "-ms-flex-order": "<integer>",
      "-ms-flex-positive": "<'flex-grow'>",
      "-ms-flex-preferred-size": "<'flex-basis'>",
      "-ms-interpolation-mode": "nearest-neighbor|bicubic",
      "-ms-grid-column-align": "start|end|center|stretch",
      "-ms-grid-row-align": "start|end|center|stretch",
      "-ms-hyphenate-limit-last": "none|always|column|page|spread",
      "-webkit-background-clip": "[<box>|border|padding|content|text]#",
      "-webkit-column-break-after": "always|auto|avoid",
      "-webkit-column-break-before": "always|auto|avoid",
      "-webkit-column-break-inside": "always|auto|avoid",
      "-webkit-font-smoothing": "auto|none|antialiased|subpixel-antialiased",
      "-webkit-mask-box-image": "[<url>|<gradient>|none] [<length-percentage>{4} <-webkit-mask-box-repeat>{2}]?",
      "-webkit-print-color-adjust": "economy|exact",
      "-webkit-text-security": "none|circle|disc|square",
      "-webkit-user-drag": "none|element|auto",
      "-webkit-user-select": "auto|none|text|all",
      "alignment-baseline": "auto|baseline|before-edge|text-before-edge|middle|central|after-edge|text-after-edge|ideographic|alphabetic|hanging|mathematical",
      "baseline-shift": "baseline|sub|super|<svg-length>",
      "behavior": "<url>+",
      "clip-rule": "nonzero|evenodd",
      "cue": "<'cue-before'> <'cue-after'>?",
      "cue-after": "<url> <decibel>?|none",
      "cue-before": "<url> <decibel>?|none",
      "dominant-baseline": "auto|use-script|no-change|reset-size|ideographic|alphabetic|hanging|mathematical|central|middle|text-after-edge|text-before-edge",
      "fill": "<paint>",
      "fill-opacity": "<number-zero-one>",
      "fill-rule": "nonzero|evenodd",
      "glyph-orientation-horizontal": "<angle>",
      "glyph-orientation-vertical": "<angle>",
      "kerning": "auto|<svg-length>",
      "marker": "none|<url>",
      "marker-end": "none|<url>",
      "marker-mid": "none|<url>",
      "marker-start": "none|<url>",
      "pause": "<'pause-before'> <'pause-after'>?",
      "pause-after": "<time>|none|x-weak|weak|medium|strong|x-strong",
      "pause-before": "<time>|none|x-weak|weak|medium|strong|x-strong",
      "rest": "<'rest-before'> <'rest-after'>?",
      "rest-after": "<time>|none|x-weak|weak|medium|strong|x-strong",
      "rest-before": "<time>|none|x-weak|weak|medium|strong|x-strong",
      "shape-rendering": "auto|optimizeSpeed|crispEdges|geometricPrecision",
      "src": "[<url> [format( <string># )]?|local( <family-name> )]#",
      "speak": "auto|none|normal",
      "speak-as": "normal|spell-out||digits||[literal-punctuation|no-punctuation]",
      "stroke": "<paint>",
      "stroke-dasharray": "none|[<svg-length>+]#",
      "stroke-dashoffset": "<svg-length>",
      "stroke-linecap": "butt|round|square",
      "stroke-linejoin": "miter|round|bevel",
      "stroke-miterlimit": "<number-one-or-greater>",
      "stroke-opacity": "<number-zero-one>",
      "stroke-width": "<svg-length>",
      "text-anchor": "start|middle|end",
      "unicode-range": "<urange>#",
      "voice-balance": "<number>|left|center|right|leftwards|rightwards",
      "voice-duration": "auto|<time>",
      "voice-family": "[[<family-name>|<generic-voice>] ,]* [<family-name>|<generic-voice>]|preserve",
      "voice-pitch": "<frequency>&&absolute|[[x-low|low|medium|high|x-high]||[<frequency>|<semitones>|<percentage>]]",
      "voice-range": "<frequency>&&absolute|[[x-low|low|medium|high|x-high]||[<frequency>|<semitones>|<percentage>]]",
      "voice-rate": "[normal|x-slow|slow|medium|fast|x-fast]||<percentage>",
      "voice-stress": "normal|strong|moderate|none|reduced",
      "voice-volume": "silent|[[x-soft|soft|medium|loud|x-loud]||<decibel>]"
    },
    "atrules": {
      "charset": {
        "prelude": "<string>",
        "descriptors": null
      },
      "counter-style": {
        "prelude": "<counter-style-name>",
        "descriptors": {
          "additive-symbols": "[<integer>&&<symbol>]#",
          "fallback": "<counter-style-name>",
          "negative": "<symbol> <symbol>?",
          "pad": "<integer>&&<symbol>",
          "prefix": "<symbol>",
          "range": "[[<integer>|infinite]{2}]#|auto",
          "speak-as": "auto|bullets|numbers|words|spell-out|<counter-style-name>",
          "suffix": "<symbol>",
          "symbols": "<symbol>+",
          "system": "cyclic|numeric|alphabetic|symbolic|additive|[fixed <integer>?]|[extends <counter-style-name>]"
        }
      },
      "document": {
        "prelude": "[<url>|url-prefix( <string> )|domain( <string> )|media-document( <string> )|regexp( <string> )]#",
        "descriptors": null
      },
      "font-face": {
        "prelude": null,
        "descriptors": {
          "ascent-override": "normal|<percentage>",
          "descent-override": "normal|<percentage>",
          "font-display": "[auto|block|swap|fallback|optional]",
          "font-family": "<family-name>",
          "font-feature-settings": "normal|<feature-tag-value>#",
          "font-variation-settings": "normal|[<string> <number>]#",
          "font-stretch": "<font-stretch-absolute>{1,2}",
          "font-style": "normal|italic|oblique <angle>{0,2}",
          "font-weight": "<font-weight-absolute>{1,2}",
          "font-variant": "normal|none|[<common-lig-values>||<discretionary-lig-values>||<historical-lig-values>||<contextual-alt-values>||stylistic( <feature-value-name> )||historical-forms||styleset( <feature-value-name># )||character-variant( <feature-value-name># )||swash( <feature-value-name> )||ornaments( <feature-value-name> )||annotation( <feature-value-name> )||[small-caps|all-small-caps|petite-caps|all-petite-caps|unicase|titling-caps]||<numeric-figure-values>||<numeric-spacing-values>||<numeric-fraction-values>||ordinal||slashed-zero||<east-asian-variant-values>||<east-asian-width-values>||ruby]",
          "line-gap-override": "normal|<percentage>",
          "size-adjust": "<percentage>",
          "src": "[<url> [format( <string># )]?|local( <family-name> )]#",
          "unicode-range": "<urange>#"
        }
      },
      "font-feature-values": {
        "prelude": "<family-name>#",
        "descriptors": null
      },
      "import": {
        "prelude": "[<string>|<url>] [layer|layer( <layer-name> )]? [supports( [<supports-condition>|<declaration>] )]? <media-query-list>?",
        "descriptors": null
      },
      "keyframes": {
        "prelude": "<keyframes-name>",
        "descriptors": null
      },
      "layer": {
        "prelude": "[<layer-name>#|<layer-name>?]",
        "descriptors": null
      },
      "media": {
        "prelude": "<media-query-list>",
        "descriptors": null
      },
      "namespace": {
        "prelude": "<namespace-prefix>? [<string>|<url>]",
        "descriptors": null
      },
      "page": {
        "prelude": "<page-selector-list>",
        "descriptors": {
          "bleed": "auto|<length>",
          "marks": "none|[crop||cross]",
          "size": "<length>{1,2}|auto|[<page-size>||[portrait|landscape]]"
        }
      },
      "property": {
        "prelude": "<custom-property-name>",
        "descriptors": {
          "syntax": "<string>",
          "inherits": "true|false",
          "initial-value": "<string>"
        }
      },
      "scroll-timeline": {
        "prelude": "<timeline-name>",
        "descriptors": null
      },
      "supports": {
        "prelude": "<supports-condition>",
        "descriptors": null
      },
      "viewport": {
        "prelude": null,
        "descriptors": {
          "height": "<viewport-length>{1,2}",
          "max-height": "<viewport-length>",
          "max-width": "<viewport-length>",
          "max-zoom": "auto|<number>|<percentage>",
          "min-height": "<viewport-length>",
          "min-width": "<viewport-length>",
          "min-zoom": "auto|<number>|<percentage>",
          "orientation": "auto|portrait|landscape",
          "user-zoom": "zoom|fixed",
          "viewport-fit": "auto|contain|cover",
          "width": "<viewport-length>{1,2}",
          "zoom": "auto|<number>|<percentage>"
        }
      },
      "nest": {
        "prelude": "<complex-selector-list>",
        "descriptors": null
      }
    }
  },
  node: node$1
}, scope$1 = {};

const types$X = types$1I;

var _default$6 = function(context) {
  switch (this.tokenType) {
   case types$X.Hash:
    return this.Hash();

   case types$X.Comma:
    return this.Operator();

   case types$X.LeftParenthesis:
    return this.Parentheses(this.readSequence, context.recognizer);

   case types$X.LeftSquareBracket:
    return this.Brackets(this.readSequence, context.recognizer);

   case types$X.String:
    return this.String();

   case types$X.Dimension:
    return this.Dimension();

   case types$X.Percentage:
    return this.Percentage();

   case types$X.Number:
    return this.Number();

   case types$X.Function:
    return this.cmpStr(this.tokenStart, this.tokenEnd, "url(") ? this.Url() : this.Function(this.readSequence, context.recognizer);

   case types$X.Url:
    return this.Url();

   case types$X.Ident:
    return this.cmpChar(this.tokenStart, 117) && this.cmpChar(this.tokenStart + 1, 43) ? this.UnicodeRange() : this.Identifier();

   case types$X.Delim:
    {
      const code = this.charCodeAt(this.tokenStart);
      if (47 === code || 42 === code || 43 === code || 45 === code) return this.Operator();
      35 === code && this.error("Hex or identifier is expected", this.tokenStart + 1);
      break;
    }
  }
};

const types$W = types$1I;

const types$V = types$1I;

var _var$3 = function() {
  const children = this.createList();
  if (this.skipSC(), children.push(this.Identifier()), this.skipSC(), this.tokenType === types$V.Comma) {
    children.push(this.Operator());
    const startIndex = this.tokenIndex, value = this.parseCustomProperty ? this.Value(null) : this.Raw(this.tokenIndex, this.consumeUntilExclamationMarkOrSemicolon, !1);
    if ("Value" === value.type && value.children.isEmpty) for (let offset = startIndex - this.tokenIndex; offset <= 0; offset++) if (this.lookupType(offset) === types$V.WhiteSpace) {
      value.children.appendData({
        type: "WhiteSpace",
        loc: null,
        value: " "
      });
      break;
    }
    children.push(value);
  }
  return children;
};

function isPlusMinusOperator$1(node) {
  return null !== node && "Operator" === node.type && ("-" === node.value[node.value.length - 1] || "+" === node.value[node.value.length - 1]);
}

const atrulePrelude$2 = {
  getNode: _default$6
}, selector$4 = {
  onWhiteSpace: function(next, children) {
    null !== children.last && "Combinator" !== children.last.type && null !== next && "Combinator" !== next.type && children.push({
      type: "Combinator",
      loc: null,
      name: " "
    });
  },
  getNode: function() {
    switch (this.tokenType) {
     case types$W.LeftSquareBracket:
      return this.AttributeSelector();

     case types$W.Hash:
      return this.IdSelector();

     case types$W.Colon:
      return this.lookupType(1) === types$W.Colon ? this.PseudoElementSelector() : this.PseudoClassSelector();

     case types$W.Ident:
      return this.TypeSelector();

     case types$W.Number:
     case types$W.Percentage:
      return this.Percentage();

     case types$W.Dimension:
      46 === this.charCodeAt(this.tokenStart) && this.error("Identifier is expected", this.tokenStart + 1);
      break;

     case types$W.Delim:
      switch (this.charCodeAt(this.tokenStart)) {
       case 43:
       case 62:
       case 126:
       case 47:
        return this.Combinator();

       case 46:
        return this.ClassSelector();

       case 42:
       case 124:
        return this.TypeSelector();

       case 35:
        return this.IdSelector();

       case 38:
        return this.NestingSelector();
      }
      break;
    }
  }
}, value$2 = {
  getNode: _default$6,
  onWhiteSpace(next, children) {
    isPlusMinusOperator$1(next) && (next.value = " " + next.value), isPlusMinusOperator$1(children.last) && (children.last.value += " ");
  },
  "expression": function() {
    return this.createSingleNodeList(this.Raw(this.tokenIndex, null, !1));
  },
  "var": _var$3
};

scope$1.AtrulePrelude = atrulePrelude$2, scope$1.Selector = selector$4, scope$1.Value = value$2;

const types$U = types$1I;

const types$T = types$1I;

function consumeRaw$6() {
  return this.createSingleNodeList(this.Raw(this.tokenIndex, null, !1));
}

function parentheses$1() {
  return this.skipSC(), this.tokenType === types$T.Ident && this.lookupNonWSType(1) === types$T.Colon ? this.createSingleNodeList(this.Declaration()) : readSequence$2.call(this);
}

function readSequence$2() {
  const children = this.createList();
  let child;
  this.skipSC();
  scan: for (;!this.eof; ) {
    switch (this.tokenType) {
     case types$T.Comment:
     case types$T.WhiteSpace:
      this.next();
      continue;

     case types$T.Function:
      child = this.Function(consumeRaw$6, this.scope.AtrulePrelude);
      break;

     case types$T.Ident:
      child = this.Identifier();
      break;

     case types$T.LeftParenthesis:
      child = this.Parentheses(parentheses$1, this.scope.AtrulePrelude);
      break;

     default:
      break scan;
    }
    children.push(child);
  }
  return children;
}

var atrule_1$1 = {
  "font-face": {
    parse: {
      prelude: null,
      block() {
        return this.Block(!0);
      }
    }
  },
  "import": {
    parse: {
      prelude() {
        const children = this.createList();
        switch (this.skipSC(), this.tokenType) {
         case types$U.String:
          children.push(this.String());
          break;

         case types$U.Url:
         case types$U.Function:
          children.push(this.Url());
          break;

         default:
          this.error("String or url() is expected");
        }
        return this.lookupNonWSType(0) !== types$U.Ident && this.lookupNonWSType(0) !== types$U.LeftParenthesis || children.push(this.MediaQueryList()), 
        children;
      },
      block: null
    }
  },
  media: {
    parse: {
      prelude() {
        return this.createSingleNodeList(this.MediaQueryList());
      },
      block(isStyleBlock = !1) {
        return this.Block(isStyleBlock);
      }
    }
  },
  nest: {
    parse: {
      prelude() {
        return this.createSingleNodeList(this.SelectorList());
      },
      block() {
        return this.Block(!0);
      }
    }
  },
  page: {
    parse: {
      prelude() {
        return this.createSingleNodeList(this.SelectorList());
      },
      block() {
        return this.Block(!0);
      }
    }
  },
  supports: {
    parse: {
      prelude() {
        const children = readSequence$2.call(this);
        return null === this.getFirstListNode(children) && this.error("Condition is expected"), 
        children;
      },
      block(isStyleBlock = !1) {
        return this.Block(isStyleBlock);
      }
    }
  }
};

const selectorList$1 = {
  parse() {
    return this.createSingleNodeList(this.SelectorList());
  }
}, selector$3 = {
  parse() {
    return this.createSingleNodeList(this.Selector());
  }
}, identList$1 = {
  parse() {
    return this.createSingleNodeList(this.Identifier());
  }
}, nth$1 = {
  parse() {
    return this.createSingleNodeList(this.Nth());
  }
};

var pseudo_1$1 = {
  "dir": identList$1,
  "has": selectorList$1,
  "lang": identList$1,
  "matches": selectorList$1,
  "is": selectorList$1,
  "-moz-any": selectorList$1,
  "-webkit-any": selectorList$1,
  "where": selectorList$1,
  "not": selectorList$1,
  "nth-child": nth$1,
  "nth-last-child": nth$1,
  "nth-last-of-type": nth$1,
  "nth-of-type": nth$1,
  "slotted": selector$3,
  "host": selector$3,
  "host-context": selector$3
}, indexParse$3 = {};

const AnPlusB$3 = AnPlusB$5, Atrule$7 = Atrule$9, AtrulePrelude$3 = AtrulePrelude$5, AttributeSelector$5 = AttributeSelector$7, Block$3 = Block$5, Brackets$3 = Brackets$5, CDC$4 = CDC$6, CDO$4 = CDO$6, ClassSelector$3 = ClassSelector$5, Combinator$3 = Combinator$5, Comment$6 = Comment$8, Declaration$5 = Declaration$7, DeclarationList$3 = DeclarationList$5, Dimension$6 = Dimension$8, Function$4 = _Function$1, Hash$4 = Hash$6, Identifier$3 = Identifier$5, IdSelector$3 = IdSelector$5, MediaFeature$3 = MediaFeature$5, MediaQuery$3 = MediaQuery$5, MediaQueryList$3 = MediaQueryList$5, NestingSelector = NestingSelector$2, Nth$3 = Nth$5, Number$4 = _Number$6, Operator$3 = Operator$5, Parentheses$3 = Parentheses$5, Percentage$6 = Percentage$8, PseudoClassSelector$3 = PseudoClassSelector$5, PseudoElementSelector$3 = PseudoElementSelector$5, Ratio$3 = Ratio$5, Raw$5 = Raw$7, Rule$5 = Rule$7, Selector$4 = Selector$7, SelectorList$3 = SelectorList$5, String$3 = _String$1, StyleSheet$3 = StyleSheet$5, TypeSelector$5 = TypeSelector$7, UnicodeRange$3 = UnicodeRange$5, Url$6 = Url$8, Value$5 = Value$7, WhiteSpace$6 = WhiteSpace$8;

indexParse$3.AnPlusB = AnPlusB$3.parse, indexParse$3.Atrule = Atrule$7.parse, indexParse$3.AtrulePrelude = AtrulePrelude$3.parse, 
indexParse$3.AttributeSelector = AttributeSelector$5.parse, indexParse$3.Block = Block$3.parse, 
indexParse$3.Brackets = Brackets$3.parse, indexParse$3.CDC = CDC$4.parse, indexParse$3.CDO = CDO$4.parse, 
indexParse$3.ClassSelector = ClassSelector$3.parse, indexParse$3.Combinator = Combinator$3.parse, 
indexParse$3.Comment = Comment$6.parse, indexParse$3.Declaration = Declaration$5.parse, 
indexParse$3.DeclarationList = DeclarationList$3.parse, indexParse$3.Dimension = Dimension$6.parse, 
indexParse$3.Function = Function$4.parse, indexParse$3.Hash = Hash$4.parse, indexParse$3.Identifier = Identifier$3.parse, 
indexParse$3.IdSelector = IdSelector$3.parse, indexParse$3.MediaFeature = MediaFeature$3.parse, 
indexParse$3.MediaQuery = MediaQuery$3.parse, indexParse$3.MediaQueryList = MediaQueryList$3.parse, 
indexParse$3.NestingSelector = NestingSelector.parse, indexParse$3.Nth = Nth$3.parse, 
indexParse$3.Number = Number$4.parse, indexParse$3.Operator = Operator$3.parse, 
indexParse$3.Parentheses = Parentheses$3.parse, indexParse$3.Percentage = Percentage$6.parse, 
indexParse$3.PseudoClassSelector = PseudoClassSelector$3.parse, indexParse$3.PseudoElementSelector = PseudoElementSelector$3.parse, 
indexParse$3.Ratio = Ratio$3.parse, indexParse$3.Raw = Raw$5.parse, indexParse$3.Rule = Rule$5.parse, 
indexParse$3.Selector = Selector$4.parse, indexParse$3.SelectorList = SelectorList$3.parse, 
indexParse$3.String = String$3.parse, indexParse$3.StyleSheet = StyleSheet$3.parse, 
indexParse$3.TypeSelector = TypeSelector$5.parse, indexParse$3.UnicodeRange = UnicodeRange$3.parse, 
indexParse$3.Url = Url$6.parse, indexParse$3.Value = Value$5.parse, indexParse$3.WhiteSpace = WhiteSpace$6.parse;

var syntax_1$1 = create_1$1({
  ...lexer$6,
  ...{
    parseContext: {
      default: "StyleSheet",
      stylesheet: "StyleSheet",
      atrule: "Atrule",
      atrulePrelude(options) {
        return this.AtrulePrelude(options.atrule ? String(options.atrule) : null);
      },
      mediaQueryList: "MediaQueryList",
      mediaQuery: "MediaQuery",
      rule: "Rule",
      selectorList: "SelectorList",
      selector: "Selector",
      block() {
        return this.Block(!0);
      },
      declarationList: "DeclarationList",
      declaration: "Declaration",
      value: "Value"
    },
    scope: scope$1,
    atrule: atrule_1$1,
    pseudo: pseudo_1$1,
    node: indexParse$3
  },
  ...{
    node: node$1
  }
}), definitionSyntax$1 = {};

const SyntaxError$6 = _SyntaxError$2, generate$N = generate$1u, parse$N = parse$1u, walk$7 = walk$a;

definitionSyntax$1.SyntaxError = SyntaxError$6.SyntaxError, definitionSyntax$1.generate = generate$N.generate, 
definitionSyntax$1.parse = parse$N.parse, definitionSyntax$1.walk = walk$7.walk;

var clone$5 = {};

const List$9 = List$f;

clone$5.clone = function clone$4(node) {
  const result = {};
  for (const key in node) {
    let value = node[key];
    value && (Array.isArray(value) || value instanceof List$9.List ? value = value.map(clone$4) : value.constructor === Object && (value = clone$4(value))), 
    result[key] = value;
  }
  return result;
};

var ident$3 = {};

const charCodeDefinitions$d = charCodeDefinitions$p, utils$l = utils$u;

ident$3.decode = function(str) {
  const end = str.length - 1;
  let decoded = "";
  for (let i = 0; i < str.length; i++) {
    let code = str.charCodeAt(i);
    if (92 === code) {
      if (i === end) break;
      if (code = str.charCodeAt(++i), charCodeDefinitions$d.isValidEscape(92, code)) {
        const escapeStart = i - 1, escapeEnd = utils$l.consumeEscaped(str, escapeStart);
        i = escapeEnd - 1, decoded += utils$l.decodeEscaped(str.substring(escapeStart + 1, escapeEnd));
      } else 0x000d === code && 0x000a === str.charCodeAt(i + 1) && i++;
    } else decoded += str[i];
  }
  return decoded;
}, ident$3.encode = function(str) {
  let encoded = "";
  if (1 === str.length && 0x002D === str.charCodeAt(0)) return "\\-";
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    0x0000 !== code ? code <= 0x001F || 0x007F === code || code >= 0x0030 && code <= 0x0039 && (0 === i || 1 === i && 0x002D === str.charCodeAt(0)) ? encoded += "\\" + code.toString(16) + " " : charCodeDefinitions$d.isName(code) ? encoded += str.charAt(i) : encoded += "\\" + str.charAt(i) : encoded += "�";
  }
  return encoded;
};

const index$1$3 = syntax_1$1, create$8 = create_1$1, List$8 = List$f, Lexer$4 = Lexer$7, index$c = definitionSyntax$1, clone$3 = clone$5, names$1$1 = names$c, ident$2 = ident$3, string$4 = string$7, url$3 = url$5, types$S = types$1I, names$9 = names$g, TokenStream$5 = TokenStream$9, {tokenize: tokenize$3, parse: parse$M, generate: generate$M, lexer: lexer$4, createLexer: createLexer$1, walk: walk$6, find: find$2, findLast: findLast$2, findAll: findAll$2, toPlainObject: toPlainObject$2, fromPlainObject: fromPlainObject$2, fork: fork$1} = index$1$3;

cjs$2.version = "2.3.1".version, cjs$2.createSyntax = create$8, cjs$2.List = List$8.List, 
cjs$2.Lexer = Lexer$4.Lexer, cjs$2.definitionSyntax = index$c, cjs$2.clone = clone$3.clone, 
cjs$2.isCustomProperty = names$1$1.isCustomProperty, cjs$2.keyword = names$1$1.keyword, 
cjs$2.property = names$1$1.property, cjs$2.vendorPrefix = names$1$1.vendorPrefix, 
cjs$2.ident = ident$2, cjs$2.string = string$4, cjs$2.url = url$3, cjs$2.tokenTypes = types$S, 
cjs$2.tokenNames = names$9, cjs$2.TokenStream = TokenStream$5.TokenStream, cjs$2.createLexer = createLexer$1, 
cjs$2.find = find$2, cjs$2.findAll = findAll$2, cjs$2.findLast = findLast$2, cjs$2.fork = fork$1, 
cjs$2.fromPlainObject = fromPlainObject$2, cjs$2.generate = generate$M, cjs$2.lexer = lexer$4, 
cjs$2.parse = parse$M, cjs$2.toPlainObject = toPlainObject$2, cjs$2.tokenize = tokenize$3, 
cjs$2.walk = walk$6;

var cjs$1 = {}, syntax$2 = {}, cjs = {}, tokenizer$2 = {}, types$R = {};

types$R.AtKeyword = 3, types$R.BadString = 6, types$R.BadUrl = 8, types$R.CDC = 15, 
types$R.CDO = 14, types$R.Colon = 16, types$R.Comma = 18, types$R.Comment = 25, 
types$R.Delim = 9, types$R.Dimension = 12, types$R.EOF = 0, types$R.Function = 2, 
types$R.Hash = 4, types$R.Ident = 1, types$R.LeftCurlyBracket = 23, types$R.LeftParenthesis = 21, 
types$R.LeftSquareBracket = 19, types$R.Number = 10, types$R.Percentage = 11, types$R.RightCurlyBracket = 24, 
types$R.RightParenthesis = 22, types$R.RightSquareBracket = 20, types$R.Semicolon = 17, 
types$R.String = 5, types$R.Url = 7, types$R.WhiteSpace = 13;

var charCodeDefinitions$c = {};

const EOF = 0;

function isDigit$1(code) {
  return code >= 0x0030 && code <= 0x0039;
}

function isUppercaseLetter(code) {
  return code >= 0x0041 && code <= 0x005A;
}

function isLowercaseLetter(code) {
  return code >= 0x0061 && code <= 0x007A;
}

function isLetter(code) {
  return isUppercaseLetter(code) || isLowercaseLetter(code);
}

function isNonAscii(code) {
  return code >= 0x0080;
}

function isNameStart(code) {
  return isLetter(code) || isNonAscii(code) || 0x005F === code;
}

function isNonPrintable(code) {
  return code >= 0x0000 && code <= 0x0008 || 0x000B === code || code >= 0x000E && code <= 0x001F || 0x007F === code;
}

function isNewline(code) {
  return 0x000A === code || 0x000D === code || 0x000C === code;
}

function isWhiteSpace(code) {
  return isNewline(code) || 0x0020 === code || 0x0009 === code;
}

function isValidEscape(first, second) {
  return 0x005C === first && (!isNewline(second) && second !== EOF);
}

const CATEGORY = new Array(0x80);

for (let i = 0; i < CATEGORY.length; i++) CATEGORY[i] = (isWhiteSpace(i) ? 130 : isDigit$1(i) && 131) || isNameStart(i) && 132 || isNonPrintable(i) && 133 || i || 128;

charCodeDefinitions$c.DigitCategory = 131, charCodeDefinitions$c.EofCategory = 128, 
charCodeDefinitions$c.NameStartCategory = 132, charCodeDefinitions$c.NonPrintableCategory = 133, 
charCodeDefinitions$c.WhiteSpaceCategory = 130, charCodeDefinitions$c.charCodeCategory = function(code) {
  return code < 0x80 ? CATEGORY[code] : 132;
}, charCodeDefinitions$c.isBOM = function(code) {
  return 0xFEFF === code || 0xFFFE === code ? 1 : 0;
}, charCodeDefinitions$c.isDigit = isDigit$1, charCodeDefinitions$c.isHexDigit = function(code) {
  return isDigit$1(code) || code >= 0x0041 && code <= 0x0046 || code >= 0x0061 && code <= 0x0066;
}, charCodeDefinitions$c.isIdentifierStart = function(first, second, third) {
  return 0x002D === first ? isNameStart(second) || 0x002D === second || isValidEscape(second, third) : !!isNameStart(first) || 0x005C === first && isValidEscape(first, second);
}, charCodeDefinitions$c.isLetter = isLetter, charCodeDefinitions$c.isLowercaseLetter = isLowercaseLetter, 
charCodeDefinitions$c.isName = function(code) {
  return isNameStart(code) || isDigit$1(code) || 0x002D === code;
}, charCodeDefinitions$c.isNameStart = isNameStart, charCodeDefinitions$c.isNewline = isNewline, 
charCodeDefinitions$c.isNonAscii = isNonAscii, charCodeDefinitions$c.isNonPrintable = isNonPrintable, 
charCodeDefinitions$c.isNumberStart = function(first, second, third) {
  return 0x002B === first || 0x002D === first ? isDigit$1(second) ? 2 : 0x002E === second && isDigit$1(third) ? 3 : 0 : 0x002E === first ? isDigit$1(second) ? 2 : 0 : isDigit$1(first) ? 1 : 0;
}, charCodeDefinitions$c.isUppercaseLetter = isUppercaseLetter, charCodeDefinitions$c.isValidEscape = isValidEscape, 
charCodeDefinitions$c.isWhiteSpace = isWhiteSpace;

var utils$k = {};

const charCodeDefinitions$b = charCodeDefinitions$c;

function getCharCode(source, offset) {
  return offset < source.length ? source.charCodeAt(offset) : 0;
}

function getNewlineLength(source, offset, code) {
  return 13 === code && 10 === getCharCode(source, offset + 1) ? 2 : 1;
}

function cmpChar(testStr, offset, referenceCode) {
  let code = testStr.charCodeAt(offset);
  return charCodeDefinitions$b.isUppercaseLetter(code) && (code |= 32), code === referenceCode;
}

function findDecimalNumberEnd(source, offset) {
  for (;offset < source.length && charCodeDefinitions$b.isDigit(source.charCodeAt(offset)); offset++) ;
  return offset;
}

function consumeEscaped(source, offset) {
  if (offset += 2, charCodeDefinitions$b.isHexDigit(getCharCode(source, offset - 1))) {
    for (const maxOffset = Math.min(source.length, offset + 5); offset < maxOffset && charCodeDefinitions$b.isHexDigit(getCharCode(source, offset)); offset++) ;
    const code = getCharCode(source, offset);
    charCodeDefinitions$b.isWhiteSpace(code) && (offset += getNewlineLength(source, offset, code));
  }
  return offset;
}

utils$k.cmpChar = cmpChar, utils$k.cmpStr = function(testStr, start, end, referenceStr) {
  if (end - start !== referenceStr.length) return !1;
  if (start < 0 || end > testStr.length) return !1;
  for (let i = start; i < end; i++) {
    const referenceCode = referenceStr.charCodeAt(i - start);
    let testCode = testStr.charCodeAt(i);
    if (charCodeDefinitions$b.isUppercaseLetter(testCode) && (testCode |= 32), testCode !== referenceCode) return !1;
  }
  return !0;
}, utils$k.consumeBadUrlRemnants = function(source, offset) {
  for (;offset < source.length; offset++) {
    const code = source.charCodeAt(offset);
    if (0x0029 === code) {
      offset++;
      break;
    }
    charCodeDefinitions$b.isValidEscape(code, getCharCode(source, offset + 1)) && (offset = consumeEscaped(source, offset));
  }
  return offset;
}, utils$k.consumeEscaped = consumeEscaped, utils$k.consumeName = function(source, offset) {
  for (;offset < source.length; offset++) {
    const code = source.charCodeAt(offset);
    if (!charCodeDefinitions$b.isName(code)) {
      if (!charCodeDefinitions$b.isValidEscape(code, getCharCode(source, offset + 1))) break;
      offset = consumeEscaped(source, offset) - 1;
    }
  }
  return offset;
}, utils$k.consumeNumber = function(source, offset) {
  let code = source.charCodeAt(offset);
  if (0x002B !== code && 0x002D !== code || (code = source.charCodeAt(offset += 1)), 
  charCodeDefinitions$b.isDigit(code) && (offset = findDecimalNumberEnd(source, offset + 1), 
  code = source.charCodeAt(offset)), 0x002E === code && charCodeDefinitions$b.isDigit(source.charCodeAt(offset + 1)) && (offset = findDecimalNumberEnd(source, offset += 2)), 
  cmpChar(source, offset, 101)) {
    let sign = 0;
    code = source.charCodeAt(offset + 1), 0x002D !== code && 0x002B !== code || (sign = 1, 
    code = source.charCodeAt(offset + 2)), charCodeDefinitions$b.isDigit(code) && (offset = findDecimalNumberEnd(source, offset + 1 + sign + 1));
  }
  return offset;
}, utils$k.decodeEscaped = function(escaped) {
  if (1 === escaped.length && !charCodeDefinitions$b.isHexDigit(escaped.charCodeAt(0))) return escaped[0];
  let code = parseInt(escaped, 16);
  return (0 === code || code >= 0xD800 && code <= 0xDFFF || code > 0x10FFFF) && (code = 0xFFFD), 
  String.fromCodePoint(code);
}, utils$k.findDecimalNumberEnd = findDecimalNumberEnd, utils$k.findWhiteSpaceEnd = function(source, offset) {
  for (;offset < source.length && charCodeDefinitions$b.isWhiteSpace(source.charCodeAt(offset)); offset++) ;
  return offset;
}, utils$k.findWhiteSpaceStart = function(source, offset) {
  for (;offset >= 0 && charCodeDefinitions$b.isWhiteSpace(source.charCodeAt(offset)); offset--) ;
  return offset + 1;
}, utils$k.getNewlineLength = getNewlineLength;

var names$8 = [ "EOF-token", "ident-token", "function-token", "at-keyword-token", "hash-token", "string-token", "bad-string-token", "url-token", "bad-url-token", "delim-token", "number-token", "percentage-token", "dimension-token", "whitespace-token", "CDO-token", "CDC-token", "colon-token", "semicolon-token", "comma-token", "[-token", "]-token", "(-token", ")-token", "{-token", "}-token" ], OffsetToLocation$3 = {}, adoptBuffer$3 = {};

adoptBuffer$3.adoptBuffer = function(buffer = null, size) {
  return null === buffer || buffer.length < size ? new Uint32Array(Math.max(size + 1024, 16384)) : buffer;
};

const adoptBuffer$1 = adoptBuffer$3, charCodeDefinitions$a = charCodeDefinitions$c;

function computeLinesAndColumns(host) {
  const source = host.source, sourceLength = source.length, startOffset = source.length > 0 ? charCodeDefinitions$a.isBOM(source.charCodeAt(0)) : 0, lines = adoptBuffer$1.adoptBuffer(host.lines, sourceLength), columns = adoptBuffer$1.adoptBuffer(host.columns, sourceLength);
  let line = host.startLine, column = host.startColumn;
  for (let i = startOffset; i < sourceLength; i++) {
    const code = source.charCodeAt(i);
    lines[i] = line, columns[i] = column++, 10 !== code && 13 !== code && 12 !== code || (13 === code && i + 1 < sourceLength && 10 === source.charCodeAt(i + 1) && (i++, 
    lines[i] = line, columns[i] = column), line++, column = 1);
  }
  lines[sourceLength] = line, columns[sourceLength] = column, host.lines = lines, 
  host.columns = columns, host.computed = !0;
}

OffsetToLocation$3.OffsetToLocation = class {
  constructor() {
    this.lines = null, this.columns = null, this.computed = !1;
  }
  setSource(source, startOffset = 0, startLine = 1, startColumn = 1) {
    this.source = source, this.startOffset = startOffset, this.startLine = startLine, 
    this.startColumn = startColumn, this.computed = !1;
  }
  getLocation(offset, filename) {
    return this.computed || computeLinesAndColumns(this), {
      source: filename,
      offset: this.startOffset + offset,
      line: this.lines[offset],
      column: this.columns[offset]
    };
  }
  getLocationRange(start, end, filename) {
    return this.computed || computeLinesAndColumns(this), {
      source: filename,
      start: {
        offset: this.startOffset + start,
        line: this.lines[start],
        column: this.columns[start]
      },
      end: {
        offset: this.startOffset + end,
        line: this.lines[end],
        column: this.columns[end]
      }
    };
  }
};

var TokenStream$4 = {};

const adoptBuffer = adoptBuffer$3, utils$j = utils$k, names$7 = names$8, types$Q = types$R, balancePair$1 = new Map([ [ types$Q.Function, types$Q.RightParenthesis ], [ types$Q.LeftParenthesis, types$Q.RightParenthesis ], [ types$Q.LeftSquareBracket, types$Q.RightSquareBracket ], [ types$Q.LeftCurlyBracket, types$Q.RightCurlyBracket ] ]);

TokenStream$4.TokenStream = class {
  constructor(source, tokenize) {
    this.setSource(source, tokenize);
  }
  reset() {
    this.eof = !1, this.tokenIndex = -1, this.tokenType = 0, this.tokenStart = this.firstCharOffset, 
    this.tokenEnd = this.firstCharOffset;
  }
  setSource(source = "", tokenize = (() => {})) {
    const sourceLength = (source = String(source || "")).length, offsetAndType = adoptBuffer.adoptBuffer(this.offsetAndType, source.length + 1), balance = adoptBuffer.adoptBuffer(this.balance, source.length + 1);
    let tokenCount = 0, balanceCloseType = 0, balanceStart = 0, firstCharOffset = -1;
    for (this.offsetAndType = null, this.balance = null, tokenize(source, ((type, start, end) => {
      switch (type) {
       default:
        balance[tokenCount] = sourceLength;
        break;

       case balanceCloseType:
        {
          let balancePrev = 16777215 & balanceStart;
          for (balanceStart = balance[balancePrev], balanceCloseType = balanceStart >> 24, 
          balance[tokenCount] = balancePrev, balance[balancePrev++] = tokenCount; balancePrev < tokenCount; balancePrev++) balance[balancePrev] === sourceLength && (balance[balancePrev] = tokenCount);
          break;
        }

       case types$Q.LeftParenthesis:
       case types$Q.Function:
       case types$Q.LeftSquareBracket:
       case types$Q.LeftCurlyBracket:
        balance[tokenCount] = balanceStart, balanceCloseType = balancePair$1.get(type), 
        balanceStart = balanceCloseType << 24 | tokenCount;
      }
      offsetAndType[tokenCount++] = type << 24 | end, -1 === firstCharOffset && (firstCharOffset = start);
    })), offsetAndType[tokenCount] = types$Q.EOF << 24 | sourceLength, balance[tokenCount] = sourceLength, 
    balance[sourceLength] = sourceLength; 0 !== balanceStart; ) {
      const balancePrev = 16777215 & balanceStart;
      balanceStart = balance[balancePrev], balance[balancePrev] = sourceLength;
    }
    this.source = source, this.firstCharOffset = -1 === firstCharOffset ? 0 : firstCharOffset, 
    this.tokenCount = tokenCount, this.offsetAndType = offsetAndType, this.balance = balance, 
    this.reset(), this.next();
  }
  lookupType(offset) {
    return (offset += this.tokenIndex) < this.tokenCount ? this.offsetAndType[offset] >> 24 : types$Q.EOF;
  }
  lookupOffset(offset) {
    return (offset += this.tokenIndex) < this.tokenCount ? 16777215 & this.offsetAndType[offset - 1] : this.source.length;
  }
  lookupValue(offset, referenceStr) {
    return (offset += this.tokenIndex) < this.tokenCount && utils$j.cmpStr(this.source, 16777215 & this.offsetAndType[offset - 1], 16777215 & this.offsetAndType[offset], referenceStr);
  }
  getTokenStart(tokenIndex) {
    return tokenIndex === this.tokenIndex ? this.tokenStart : tokenIndex > 0 ? tokenIndex < this.tokenCount ? 16777215 & this.offsetAndType[tokenIndex - 1] : 16777215 & this.offsetAndType[this.tokenCount] : this.firstCharOffset;
  }
  substrToCursor(start) {
    return this.source.substring(start, this.tokenStart);
  }
  isBalanceEdge(pos) {
    return this.balance[this.tokenIndex] < pos;
  }
  isDelim(code, offset) {
    return offset ? this.lookupType(offset) === types$Q.Delim && this.source.charCodeAt(this.lookupOffset(offset)) === code : this.tokenType === types$Q.Delim && this.source.charCodeAt(this.tokenStart) === code;
  }
  skip(tokenCount) {
    let next = this.tokenIndex + tokenCount;
    next < this.tokenCount ? (this.tokenIndex = next, this.tokenStart = 16777215 & this.offsetAndType[next - 1], 
    next = this.offsetAndType[next], this.tokenType = next >> 24, this.tokenEnd = 16777215 & next) : (this.tokenIndex = this.tokenCount, 
    this.next());
  }
  next() {
    let next = this.tokenIndex + 1;
    next < this.tokenCount ? (this.tokenIndex = next, this.tokenStart = this.tokenEnd, 
    next = this.offsetAndType[next], this.tokenType = next >> 24, this.tokenEnd = 16777215 & next) : (this.eof = !0, 
    this.tokenIndex = this.tokenCount, this.tokenType = types$Q.EOF, this.tokenStart = this.tokenEnd = this.source.length);
  }
  skipSC() {
    for (;this.tokenType === types$Q.WhiteSpace || this.tokenType === types$Q.Comment; ) this.next();
  }
  skipUntilBalanced(startToken, stopConsume) {
    let balanceEnd, offset, cursor = startToken;
    loop: for (;cursor < this.tokenCount && (balanceEnd = this.balance[cursor], !(balanceEnd < startToken)); cursor++) switch (offset = cursor > 0 ? 16777215 & this.offsetAndType[cursor - 1] : this.firstCharOffset, 
    stopConsume(this.source.charCodeAt(offset))) {
     case 1:
      break loop;

     case 2:
      cursor++;
      break loop;

     default:
      this.balance[balanceEnd] === cursor && (cursor = balanceEnd);
    }
    this.skip(cursor - this.tokenIndex);
  }
  forEachToken(fn) {
    for (let i = 0, offset = this.firstCharOffset; i < this.tokenCount; i++) {
      const start = offset, item = this.offsetAndType[i], end = 16777215 & item;
      offset = end, fn(item >> 24, start, end, i);
    }
  }
  dump() {
    const tokens = new Array(this.tokenCount);
    return this.forEachToken(((type, start, end, index) => {
      tokens[index] = {
        idx: index,
        type: names$7[type],
        chunk: this.source.substring(start, end),
        balance: this.balance[index]
      };
    })), tokens;
  }
};

const types$P = types$R, charCodeDefinitions$9 = charCodeDefinitions$c, utils$i = utils$k, names$6 = names$8, OffsetToLocation$1 = OffsetToLocation$3, TokenStream$2 = TokenStream$4;

tokenizer$2.AtKeyword = types$P.AtKeyword, tokenizer$2.BadString = types$P.BadString, 
tokenizer$2.BadUrl = types$P.BadUrl, tokenizer$2.CDC = types$P.CDC, tokenizer$2.CDO = types$P.CDO, 
tokenizer$2.Colon = types$P.Colon, tokenizer$2.Comma = types$P.Comma, tokenizer$2.Comment = types$P.Comment, 
tokenizer$2.Delim = types$P.Delim, tokenizer$2.Dimension = types$P.Dimension, tokenizer$2.EOF = types$P.EOF, 
tokenizer$2.Function = types$P.Function, tokenizer$2.Hash = types$P.Hash, tokenizer$2.Ident = types$P.Ident, 
tokenizer$2.LeftCurlyBracket = types$P.LeftCurlyBracket, tokenizer$2.LeftParenthesis = types$P.LeftParenthesis, 
tokenizer$2.LeftSquareBracket = types$P.LeftSquareBracket, tokenizer$2.Number = types$P.Number, 
tokenizer$2.Percentage = types$P.Percentage, tokenizer$2.RightCurlyBracket = types$P.RightCurlyBracket, 
tokenizer$2.RightParenthesis = types$P.RightParenthesis, tokenizer$2.RightSquareBracket = types$P.RightSquareBracket, 
tokenizer$2.Semicolon = types$P.Semicolon, tokenizer$2.String = types$P.String, 
tokenizer$2.Url = types$P.Url, tokenizer$2.WhiteSpace = types$P.WhiteSpace, tokenizer$2.tokenTypes = types$P, 
tokenizer$2.DigitCategory = charCodeDefinitions$9.DigitCategory, tokenizer$2.EofCategory = charCodeDefinitions$9.EofCategory, 
tokenizer$2.NameStartCategory = charCodeDefinitions$9.NameStartCategory, tokenizer$2.NonPrintableCategory = charCodeDefinitions$9.NonPrintableCategory, 
tokenizer$2.WhiteSpaceCategory = charCodeDefinitions$9.WhiteSpaceCategory, tokenizer$2.charCodeCategory = charCodeDefinitions$9.charCodeCategory, 
tokenizer$2.isBOM = charCodeDefinitions$9.isBOM, tokenizer$2.isDigit = charCodeDefinitions$9.isDigit, 
tokenizer$2.isHexDigit = charCodeDefinitions$9.isHexDigit, tokenizer$2.isIdentifierStart = charCodeDefinitions$9.isIdentifierStart, 
tokenizer$2.isLetter = charCodeDefinitions$9.isLetter, tokenizer$2.isLowercaseLetter = charCodeDefinitions$9.isLowercaseLetter, 
tokenizer$2.isName = charCodeDefinitions$9.isName, tokenizer$2.isNameStart = charCodeDefinitions$9.isNameStart, 
tokenizer$2.isNewline = charCodeDefinitions$9.isNewline, tokenizer$2.isNonAscii = charCodeDefinitions$9.isNonAscii, 
tokenizer$2.isNonPrintable = charCodeDefinitions$9.isNonPrintable, tokenizer$2.isNumberStart = charCodeDefinitions$9.isNumberStart, 
tokenizer$2.isUppercaseLetter = charCodeDefinitions$9.isUppercaseLetter, tokenizer$2.isValidEscape = charCodeDefinitions$9.isValidEscape, 
tokenizer$2.isWhiteSpace = charCodeDefinitions$9.isWhiteSpace, tokenizer$2.cmpChar = utils$i.cmpChar, 
tokenizer$2.cmpStr = utils$i.cmpStr, tokenizer$2.consumeBadUrlRemnants = utils$i.consumeBadUrlRemnants, 
tokenizer$2.consumeEscaped = utils$i.consumeEscaped, tokenizer$2.consumeName = utils$i.consumeName, 
tokenizer$2.consumeNumber = utils$i.consumeNumber, tokenizer$2.decodeEscaped = utils$i.decodeEscaped, 
tokenizer$2.findDecimalNumberEnd = utils$i.findDecimalNumberEnd, tokenizer$2.findWhiteSpaceEnd = utils$i.findWhiteSpaceEnd, 
tokenizer$2.findWhiteSpaceStart = utils$i.findWhiteSpaceStart, tokenizer$2.getNewlineLength = utils$i.getNewlineLength, 
tokenizer$2.tokenNames = names$6, tokenizer$2.OffsetToLocation = OffsetToLocation$1.OffsetToLocation, 
tokenizer$2.TokenStream = TokenStream$2.TokenStream, tokenizer$2.tokenize = function(source, onToken) {
  function getCharCode(offset) {
    return offset < sourceLength ? source.charCodeAt(offset) : 0;
  }
  function consumeNumericToken() {
    return offset = utils$i.consumeNumber(source, offset), charCodeDefinitions$9.isIdentifierStart(getCharCode(offset), getCharCode(offset + 1), getCharCode(offset + 2)) ? (type = types$P.Dimension, 
    void (offset = utils$i.consumeName(source, offset))) : 0x0025 === getCharCode(offset) ? (type = types$P.Percentage, 
    void offset++) : void (type = types$P.Number);
  }
  function consumeIdentLikeToken() {
    const nameStartOffset = offset;
    return offset = utils$i.consumeName(source, offset), utils$i.cmpStr(source, nameStartOffset, offset, "url") && 0x0028 === getCharCode(offset) ? (offset = utils$i.findWhiteSpaceEnd(source, offset + 1), 
    0x0022 === getCharCode(offset) || 0x0027 === getCharCode(offset) ? (type = types$P.Function, 
    void (offset = nameStartOffset + 4)) : void function() {
      for (type = types$P.Url, offset = utils$i.findWhiteSpaceEnd(source, offset); offset < source.length; offset++) {
        const code = source.charCodeAt(offset);
        switch (charCodeDefinitions$9.charCodeCategory(code)) {
         case 0x0029:
          return void offset++;

         case charCodeDefinitions$9.WhiteSpaceCategory:
          return offset = utils$i.findWhiteSpaceEnd(source, offset), 0x0029 === getCharCode(offset) || offset >= source.length ? void (offset < source.length && offset++) : (offset = utils$i.consumeBadUrlRemnants(source, offset), 
          void (type = types$P.BadUrl));

         case 0x0022:
         case 0x0027:
         case 0x0028:
         case charCodeDefinitions$9.NonPrintableCategory:
          return offset = utils$i.consumeBadUrlRemnants(source, offset), void (type = types$P.BadUrl);

         case 0x005C:
          if (charCodeDefinitions$9.isValidEscape(code, getCharCode(offset + 1))) {
            offset = utils$i.consumeEscaped(source, offset) - 1;
            break;
          }
          return offset = utils$i.consumeBadUrlRemnants(source, offset), void (type = types$P.BadUrl);
        }
      }
    }()) : 0x0028 === getCharCode(offset) ? (type = types$P.Function, void offset++) : void (type = types$P.Ident);
  }
  function consumeStringToken(endingCodePoint) {
    for (endingCodePoint || (endingCodePoint = getCharCode(offset++)), type = types$P.String; offset < source.length; offset++) {
      const code = source.charCodeAt(offset);
      switch (charCodeDefinitions$9.charCodeCategory(code)) {
       case endingCodePoint:
        return void offset++;

       case charCodeDefinitions$9.WhiteSpaceCategory:
        if (charCodeDefinitions$9.isNewline(code)) return offset += utils$i.getNewlineLength(source, offset, code), 
        void (type = types$P.BadString);
        break;

       case 0x005C:
        if (offset === source.length - 1) break;
        const nextCode = getCharCode(offset + 1);
        charCodeDefinitions$9.isNewline(nextCode) ? offset += utils$i.getNewlineLength(source, offset + 1, nextCode) : charCodeDefinitions$9.isValidEscape(code, nextCode) && (offset = utils$i.consumeEscaped(source, offset) - 1);
      }
    }
  }
  const sourceLength = (source = String(source || "")).length;
  let type, start = charCodeDefinitions$9.isBOM(getCharCode(0)), offset = start;
  for (;offset < sourceLength; ) {
    const code = source.charCodeAt(offset);
    switch (charCodeDefinitions$9.charCodeCategory(code)) {
     case charCodeDefinitions$9.WhiteSpaceCategory:
      type = types$P.WhiteSpace, offset = utils$i.findWhiteSpaceEnd(source, offset + 1);
      break;

     case 0x0022:
      consumeStringToken();
      break;

     case 0x0023:
      charCodeDefinitions$9.isName(getCharCode(offset + 1)) || charCodeDefinitions$9.isValidEscape(getCharCode(offset + 1), getCharCode(offset + 2)) ? (type = types$P.Hash, 
      offset = utils$i.consumeName(source, offset + 1)) : (type = types$P.Delim, offset++);
      break;

     case 0x0027:
      consumeStringToken();
      break;

     case 0x0028:
      type = types$P.LeftParenthesis, offset++;
      break;

     case 0x0029:
      type = types$P.RightParenthesis, offset++;
      break;

     case 0x002B:
      charCodeDefinitions$9.isNumberStart(code, getCharCode(offset + 1), getCharCode(offset + 2)) ? consumeNumericToken() : (type = types$P.Delim, 
      offset++);
      break;

     case 0x002C:
      type = types$P.Comma, offset++;
      break;

     case 0x002D:
      charCodeDefinitions$9.isNumberStart(code, getCharCode(offset + 1), getCharCode(offset + 2)) ? consumeNumericToken() : 0x002D === getCharCode(offset + 1) && 0x003E === getCharCode(offset + 2) ? (type = types$P.CDC, 
      offset += 3) : charCodeDefinitions$9.isIdentifierStart(code, getCharCode(offset + 1), getCharCode(offset + 2)) ? consumeIdentLikeToken() : (type = types$P.Delim, 
      offset++);
      break;

     case 0x002E:
      charCodeDefinitions$9.isNumberStart(code, getCharCode(offset + 1), getCharCode(offset + 2)) ? consumeNumericToken() : (type = types$P.Delim, 
      offset++);
      break;

     case 0x002F:
      0x002A === getCharCode(offset + 1) ? (type = types$P.Comment, offset = source.indexOf("*/", offset + 2), 
      offset = -1 === offset ? source.length : offset + 2) : (type = types$P.Delim, offset++);
      break;

     case 0x003A:
      type = types$P.Colon, offset++;
      break;

     case 0x003B:
      type = types$P.Semicolon, offset++;
      break;

     case 0x003C:
      0x0021 === getCharCode(offset + 1) && 0x002D === getCharCode(offset + 2) && 0x002D === getCharCode(offset + 3) ? (type = types$P.CDO, 
      offset += 4) : (type = types$P.Delim, offset++);
      break;

     case 0x0040:
      charCodeDefinitions$9.isIdentifierStart(getCharCode(offset + 1), getCharCode(offset + 2), getCharCode(offset + 3)) ? (type = types$P.AtKeyword, 
      offset = utils$i.consumeName(source, offset + 1)) : (type = types$P.Delim, offset++);
      break;

     case 0x005B:
      type = types$P.LeftSquareBracket, offset++;
      break;

     case 0x005C:
      charCodeDefinitions$9.isValidEscape(code, getCharCode(offset + 1)) ? consumeIdentLikeToken() : (type = types$P.Delim, 
      offset++);
      break;

     case 0x005D:
      type = types$P.RightSquareBracket, offset++;
      break;

     case 0x007B:
      type = types$P.LeftCurlyBracket, offset++;
      break;

     case 0x007D:
      type = types$P.RightCurlyBracket, offset++;
      break;

     case charCodeDefinitions$9.DigitCategory:
      consumeNumericToken();
      break;

     case charCodeDefinitions$9.NameStartCategory:
      consumeIdentLikeToken();
      break;

     default:
      type = types$P.Delim, offset++;
    }
    onToken(type, start, start = offset);
  }
};

var create$7 = {}, List$7 = {};

let releasedCursors = null, List$6 = class List {
  static createItem(data) {
    return {
      prev: null,
      next: null,
      data
    };
  }
  constructor() {
    this.head = null, this.tail = null, this.cursor = null;
  }
  createItem(data) {
    return List.createItem(data);
  }
  allocateCursor(prev, next) {
    let cursor;
    return null !== releasedCursors ? (cursor = releasedCursors, releasedCursors = releasedCursors.cursor, 
    cursor.prev = prev, cursor.next = next, cursor.cursor = this.cursor) : cursor = {
      prev,
      next,
      cursor: this.cursor
    }, this.cursor = cursor, cursor;
  }
  releaseCursor() {
    const {cursor} = this;
    this.cursor = cursor.cursor, cursor.prev = null, cursor.next = null, cursor.cursor = releasedCursors, 
    releasedCursors = cursor;
  }
  updateCursors(prevOld, prevNew, nextOld, nextNew) {
    let {cursor} = this;
    for (;null !== cursor; ) cursor.prev === prevOld && (cursor.prev = prevNew), cursor.next === nextOld && (cursor.next = nextNew), 
    cursor = cursor.cursor;
  }
  * [Symbol.iterator]() {
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) yield cursor.data;
  }
  get size() {
    let size = 0;
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) size++;
    return size;
  }
  get isEmpty() {
    return null === this.head;
  }
  get first() {
    return this.head && this.head.data;
  }
  get last() {
    return this.tail && this.tail.data;
  }
  fromArray(array) {
    let cursor = null;
    this.head = null;
    for (let data of array) {
      const item = List.createItem(data);
      null !== cursor ? cursor.next = item : this.head = item, item.prev = cursor, cursor = item;
    }
    return this.tail = cursor, this;
  }
  toArray() {
    return [ ...this ];
  }
  toJSON() {
    return [ ...this ];
  }
  forEach(fn, thisArg = this) {
    const cursor = this.allocateCursor(null, this.head);
    for (;null !== cursor.next; ) {
      const item = cursor.next;
      cursor.next = item.next, fn.call(thisArg, item.data, item, this);
    }
    this.releaseCursor();
  }
  forEachRight(fn, thisArg = this) {
    const cursor = this.allocateCursor(this.tail, null);
    for (;null !== cursor.prev; ) {
      const item = cursor.prev;
      cursor.prev = item.prev, fn.call(thisArg, item.data, item, this);
    }
    this.releaseCursor();
  }
  reduce(fn, initialValue, thisArg = this) {
    let item, cursor = this.allocateCursor(null, this.head), acc = initialValue;
    for (;null !== cursor.next; ) item = cursor.next, cursor.next = item.next, acc = fn.call(thisArg, acc, item.data, item, this);
    return this.releaseCursor(), acc;
  }
  reduceRight(fn, initialValue, thisArg = this) {
    let item, cursor = this.allocateCursor(this.tail, null), acc = initialValue;
    for (;null !== cursor.prev; ) item = cursor.prev, cursor.prev = item.prev, acc = fn.call(thisArg, acc, item.data, item, this);
    return this.releaseCursor(), acc;
  }
  some(fn, thisArg = this) {
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) if (fn.call(thisArg, cursor.data, cursor, this)) return !0;
    return !1;
  }
  map(fn, thisArg = this) {
    const result = new List;
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) result.appendData(fn.call(thisArg, cursor.data, cursor, this));
    return result;
  }
  filter(fn, thisArg = this) {
    const result = new List;
    for (let cursor = this.head; null !== cursor; cursor = cursor.next) fn.call(thisArg, cursor.data, cursor, this) && result.appendData(cursor.data);
    return result;
  }
  nextUntil(start, fn, thisArg = this) {
    if (null === start) return;
    const cursor = this.allocateCursor(null, start);
    for (;null !== cursor.next; ) {
      const item = cursor.next;
      if (cursor.next = item.next, fn.call(thisArg, item.data, item, this)) break;
    }
    this.releaseCursor();
  }
  prevUntil(start, fn, thisArg = this) {
    if (null === start) return;
    const cursor = this.allocateCursor(start, null);
    for (;null !== cursor.prev; ) {
      const item = cursor.prev;
      if (cursor.prev = item.prev, fn.call(thisArg, item.data, item, this)) break;
    }
    this.releaseCursor();
  }
  clear() {
    this.head = null, this.tail = null;
  }
  copy() {
    const result = new List;
    for (let data of this) result.appendData(data);
    return result;
  }
  prepend(item) {
    return this.updateCursors(null, item, this.head, item), null !== this.head ? (this.head.prev = item, 
    item.next = this.head) : this.tail = item, this.head = item, this;
  }
  prependData(data) {
    return this.prepend(List.createItem(data));
  }
  append(item) {
    return this.insert(item);
  }
  appendData(data) {
    return this.insert(List.createItem(data));
  }
  insert(item, before = null) {
    if (null !== before) if (this.updateCursors(before.prev, item, before, item), null === before.prev) {
      if (this.head !== before) throw new Error("before doesn't belong to list");
      this.head = item, before.prev = item, item.next = before, this.updateCursors(null, item);
    } else before.prev.next = item, item.prev = before.prev, before.prev = item, item.next = before; else this.updateCursors(this.tail, item, null, item), 
    null !== this.tail ? (this.tail.next = item, item.prev = this.tail) : this.head = item, 
    this.tail = item;
    return this;
  }
  insertData(data, before) {
    return this.insert(List.createItem(data), before);
  }
  remove(item) {
    if (this.updateCursors(item, item.prev, item, item.next), null !== item.prev) item.prev.next = item.next; else {
      if (this.head !== item) throw new Error("item doesn't belong to list");
      this.head = item.next;
    }
    if (null !== item.next) item.next.prev = item.prev; else {
      if (this.tail !== item) throw new Error("item doesn't belong to list");
      this.tail = item.prev;
    }
    return item.prev = null, item.next = null, item;
  }
  push(data) {
    this.insert(List.createItem(data));
  }
  pop() {
    return null !== this.tail ? this.remove(this.tail) : null;
  }
  unshift(data) {
    this.prepend(List.createItem(data));
  }
  shift() {
    return null !== this.head ? this.remove(this.head) : null;
  }
  prependList(list) {
    return this.insertList(list, this.head);
  }
  appendList(list) {
    return this.insertList(list);
  }
  insertList(list, before) {
    return null === list.head || (null != before ? (this.updateCursors(before.prev, list.tail, before, list.head), 
    null !== before.prev ? (before.prev.next = list.head, list.head.prev = before.prev) : this.head = list.head, 
    before.prev = list.tail, list.tail.next = before) : (this.updateCursors(this.tail, list.tail, null, list.head), 
    null !== this.tail ? (this.tail.next = list.head, list.head.prev = this.tail) : this.head = list.head, 
    this.tail = list.tail), list.head = null, list.tail = null), this;
  }
  replace(oldItem, newItemOrList) {
    "head" in newItemOrList ? this.insertList(newItemOrList, oldItem) : this.insert(newItemOrList, oldItem), 
    this.remove(oldItem);
  }
};

List$7.List = List$6;

var _SyntaxError$1 = {}, createCustomError$4 = {};

createCustomError$4.createCustomError = function(name, message) {
  const error = Object.create(SyntaxError.prototype), errorStack = new Error;
  return Object.assign(error, {
    name,
    message,
    get stack() {
      return (errorStack.stack || "").replace(/^(.+\n){1,3}/, `${name}: ${message}\n`);
    }
  });
};

const createCustomError$2 = createCustomError$4, MAX_LINE_LENGTH = 100, OFFSET_CORRECTION = 60, TAB_REPLACEMENT = "    ";

function sourceFragment({source, line, column}, extraLines) {
  function processLines(start, end) {
    return lines.slice(start, end).map(((line, idx) => String(start + idx + 1).padStart(maxNumLength) + " |" + line)).join("\n");
  }
  const lines = source.split(/\r\n?|\n|\f/), startLine = Math.max(1, line - extraLines) - 1, endLine = Math.min(line + extraLines, lines.length + 1), maxNumLength = Math.max(4, String(endLine).length) + 1;
  let cutLeft = 0;
  (column += (TAB_REPLACEMENT.length - 1) * (lines[line - 1].substr(0, column - 1).match(/\t/g) || []).length) > MAX_LINE_LENGTH && (cutLeft = column - OFFSET_CORRECTION + 3, 
  column = OFFSET_CORRECTION - 2);
  for (let i = startLine; i <= endLine; i++) i >= 0 && i < lines.length && (lines[i] = lines[i].replace(/\t/g, TAB_REPLACEMENT), 
  lines[i] = (cutLeft > 0 && lines[i].length > cutLeft ? "…" : "") + lines[i].substr(cutLeft, MAX_LINE_LENGTH - 2) + (lines[i].length > cutLeft + MAX_LINE_LENGTH - 1 ? "…" : ""));
  return [ processLines(startLine, line), new Array(column + maxNumLength + 2).join("-") + "^", processLines(line, endLine) ].filter(Boolean).join("\n");
}

_SyntaxError$1.SyntaxError = function(message, source, offset, line, column) {
  return Object.assign(createCustomError$2.createCustomError("SyntaxError", message), {
    source,
    offset,
    line,
    column,
    sourceFragment: extraLines => sourceFragment({
      source,
      line,
      column
    }, isNaN(extraLines) ? 0 : extraLines),
    get formattedMessage() {
      return `Parse error: ${message}\n` + sourceFragment({
        source,
        line,
        column
      }, 2);
    }
  });
};

var sequence$1 = {};

const types$O = types$R;

sequence$1.readSequence = function(recognizer) {
  const children = this.createList();
  let space = !1;
  const context = {
    recognizer
  };
  for (;!this.eof; ) {
    switch (this.tokenType) {
     case types$O.Comment:
      this.next();
      continue;

     case types$O.WhiteSpace:
      space = !0, this.next();
      continue;
    }
    let child = recognizer.getNode.call(this, context);
    if (void 0 === child) break;
    space && (recognizer.onWhiteSpace && recognizer.onWhiteSpace.call(this, child, children, context), 
    space = !1), children.push(child);
  }
  return space && recognizer.onWhiteSpace && recognizer.onWhiteSpace.call(this, null, children, context), 
  children;
};

const List$5 = List$7, SyntaxError$4 = _SyntaxError$1, index$b = tokenizer$2, sequence = sequence$1, OffsetToLocation = OffsetToLocation$3, TokenStream$1 = TokenStream$4, utils$h = utils$k, types$N = types$R, names$5 = names$8, NOOP = () => {};

function createParseContext(name) {
  return function() {
    return this[name]();
  };
}

function fetchParseValues(dict) {
  const result = Object.create(null);
  for (const name in dict) {
    const item = dict[name], fn = item.parse || item;
    fn && (result[name] = fn);
  }
  return result;
}

create$7.createParser = function(config) {
  let source = "", filename = "<unknown>", needPositions = !1, onParseError = NOOP, onParseErrorThrow = !1;
  const locationMap = new OffsetToLocation.OffsetToLocation, parser = Object.assign(new TokenStream$1.TokenStream, function(config) {
    const parseConfig = {
      context: Object.create(null),
      scope: Object.assign(Object.create(null), config.scope),
      atrule: fetchParseValues(config.atrule),
      pseudo: fetchParseValues(config.pseudo),
      node: fetchParseValues(config.node)
    };
    for (const name in config.parseContext) switch (typeof config.parseContext[name]) {
     case "function":
      parseConfig.context[name] = config.parseContext[name];
      break;

     case "string":
      parseConfig.context[name] = createParseContext(config.parseContext[name]);
    }
    return {
      config: parseConfig,
      ...parseConfig,
      ...parseConfig.node
    };
  }(config || {}), {
    parseAtrulePrelude: !0,
    parseRulePrelude: !0,
    parseValue: !0,
    parseCustomProperty: !1,
    readSequence: sequence.readSequence,
    consumeUntilBalanceEnd: () => 0,
    consumeUntilLeftCurlyBracket: code => 123 === code ? 1 : 0,
    consumeUntilLeftCurlyBracketOrSemicolon: code => 123 === code || 59 === code ? 1 : 0,
    consumeUntilExclamationMarkOrSemicolon: code => 33 === code || 59 === code ? 1 : 0,
    consumeUntilSemicolonIncluded: code => 59 === code ? 2 : 0,
    createList: () => new List$5.List,
    createSingleNodeList: node => (new List$5.List).appendData(node),
    getFirstListNode: list => list && list.first,
    getLastListNode: list => list && list.last,
    parseWithFallback(consumer, fallback) {
      const startToken = this.tokenIndex;
      try {
        return consumer.call(this);
      } catch (e) {
        if (onParseErrorThrow) throw e;
        const fallbackNode = fallback.call(this, startToken);
        return onParseErrorThrow = !0, onParseError(e, fallbackNode), onParseErrorThrow = !1, 
        fallbackNode;
      }
    },
    lookupNonWSType(offset) {
      let type;
      do {
        if (type = this.lookupType(offset++), type !== types$N.WhiteSpace) return type;
      } while (0 !== type);
      return 0;
    },
    charCodeAt: offset => offset >= 0 && offset < source.length ? source.charCodeAt(offset) : 0,
    substring: (offsetStart, offsetEnd) => source.substring(offsetStart, offsetEnd),
    substrToCursor(start) {
      return this.source.substring(start, this.tokenStart);
    },
    cmpChar: (offset, charCode) => utils$h.cmpChar(source, offset, charCode),
    cmpStr: (offsetStart, offsetEnd, str) => utils$h.cmpStr(source, offsetStart, offsetEnd, str),
    consume(tokenType) {
      const start = this.tokenStart;
      return this.eat(tokenType), this.substrToCursor(start);
    },
    consumeFunctionName() {
      const name = source.substring(this.tokenStart, this.tokenEnd - 1);
      return this.eat(types$N.Function), name;
    },
    consumeNumber(type) {
      const number = source.substring(this.tokenStart, utils$h.consumeNumber(source, this.tokenStart));
      return this.eat(type), number;
    },
    eat(tokenType) {
      if (this.tokenType !== tokenType) {
        const tokenName = names$5[tokenType].slice(0, -6).replace(/-/g, " ").replace(/^./, (m => m.toUpperCase()));
        let message = `${/[[\](){}]/.test(tokenName) ? `"${tokenName}"` : tokenName} is expected`, offset = this.tokenStart;
        switch (tokenType) {
         case types$N.Ident:
          this.tokenType === types$N.Function || this.tokenType === types$N.Url ? (offset = this.tokenEnd - 1, 
          message = "Identifier is expected but function found") : message = "Identifier is expected";
          break;

         case types$N.Hash:
          this.isDelim(35) && (this.next(), offset++, message = "Name is expected");
          break;

         case types$N.Percentage:
          this.tokenType === types$N.Number && (offset = this.tokenEnd, message = "Percent sign is expected");
        }
        this.error(message, offset);
      }
      this.next();
    },
    eatIdent(name) {
      this.tokenType === types$N.Ident && !1 !== this.lookupValue(0, name) || this.error(`Identifier "${name}" is expected`), 
      this.next();
    },
    eatDelim(code) {
      this.isDelim(code) || this.error(`Delim "${String.fromCharCode(code)}" is expected`), 
      this.next();
    },
    getLocation: (start, end) => needPositions ? locationMap.getLocationRange(start, end, filename) : null,
    getLocationFromList(list) {
      if (needPositions) {
        const head = this.getFirstListNode(list), tail = this.getLastListNode(list);
        return locationMap.getLocationRange(null !== head ? head.loc.start.offset - locationMap.startOffset : this.tokenStart, null !== tail ? tail.loc.end.offset - locationMap.startOffset : this.tokenStart, filename);
      }
      return null;
    },
    error(message, offset) {
      const location = void 0 !== offset && offset < source.length ? locationMap.getLocation(offset) : this.eof ? locationMap.getLocation(utils$h.findWhiteSpaceStart(source, source.length - 1)) : locationMap.getLocation(this.tokenStart);
      throw new SyntaxError$4.SyntaxError(message || "Unexpected input", source, location.offset, location.line, location.column);
    }
  });
  return Object.assign((function(source_, options) {
    source = source_, options = options || {}, parser.setSource(source, index$b.tokenize), 
    locationMap.setSource(source, options.offset, options.line, options.column), filename = options.filename || "<unknown>", 
    needPositions = Boolean(options.positions), onParseError = "function" == typeof options.onParseError ? options.onParseError : NOOP, 
    onParseErrorThrow = !1, parser.parseAtrulePrelude = !("parseAtrulePrelude" in options) || Boolean(options.parseAtrulePrelude), 
    parser.parseRulePrelude = !("parseRulePrelude" in options) || Boolean(options.parseRulePrelude), 
    parser.parseValue = !("parseValue" in options) || Boolean(options.parseValue), parser.parseCustomProperty = "parseCustomProperty" in options && Boolean(options.parseCustomProperty);
    const {context = "default", onComment} = options;
    if (context in parser.context == !1) throw new Error("Unknown context `" + context + "`");
    "function" == typeof onComment && parser.forEachToken(((type, start, end) => {
      if (type === types$N.Comment) {
        const loc = parser.getLocation(start, end), value = utils$h.cmpStr(source, end - 2, end, "*/") ? source.slice(start + 2, end - 2) : source.slice(start + 2, end);
        onComment(value, loc);
      }
    }));
    const ast = parser.context[context].call(parser, options);
    return parser.eof || parser.error(), ast;
  }), {
    SyntaxError: SyntaxError$4.SyntaxError,
    config: parser.config
  });
};

var create$6 = {}, sourceMap$1 = {};

const sourceMapGenerator_js = sourceMapGenerator, trackNodes = new Set([ "Atrule", "Selector", "Declaration" ]);

sourceMap$1.generateSourceMap = function(handlers) {
  const map = new sourceMapGenerator_js.SourceMapGenerator, generated = {
    line: 1,
    column: 0
  }, original = {
    line: 0,
    column: 0
  }, activatedGenerated = {
    line: 1,
    column: 0
  }, activatedMapping = {
    generated: activatedGenerated
  };
  let line = 1, column = 0, sourceMappingActive = !1;
  const origHandlersNode = handlers.node;
  handlers.node = function(node) {
    if (node.loc && node.loc.start && trackNodes.has(node.type)) {
      const nodeLine = node.loc.start.line, nodeColumn = node.loc.start.column - 1;
      original.line === nodeLine && original.column === nodeColumn || (original.line = nodeLine, 
      original.column = nodeColumn, generated.line = line, generated.column = column, 
      sourceMappingActive && (sourceMappingActive = !1, generated.line === activatedGenerated.line && generated.column === activatedGenerated.column || map.addMapping(activatedMapping)), 
      sourceMappingActive = !0, map.addMapping({
        source: node.loc.source,
        original,
        generated
      }));
    }
    origHandlersNode.call(this, node), sourceMappingActive && trackNodes.has(node.type) && (activatedGenerated.line = line, 
    activatedGenerated.column = column);
  };
  const origHandlersEmit = handlers.emit;
  handlers.emit = function(value, type, auto) {
    for (let i = 0; i < value.length; i++) 10 === value.charCodeAt(i) ? (line++, column = 0) : column++;
    origHandlersEmit(value, type, auto);
  };
  const origHandlersResult = handlers.result;
  return handlers.result = function() {
    return sourceMappingActive && map.addMapping(activatedMapping), {
      css: origHandlersResult(),
      map
    };
  }, handlers;
};

var tokenBefore$1 = {};

const types$M = types$R, code = (type, value) => {
  if (type === types$M.Delim && (type = value), "string" == typeof type) {
    const charCode = type.charCodeAt(0);
    return charCode > 0x7F ? 0x8000 : charCode << 8;
  }
  return type;
}, specPairs = [ [ types$M.Ident, types$M.Ident ], [ types$M.Ident, types$M.Function ], [ types$M.Ident, types$M.Url ], [ types$M.Ident, types$M.BadUrl ], [ types$M.Ident, "-" ], [ types$M.Ident, types$M.Number ], [ types$M.Ident, types$M.Percentage ], [ types$M.Ident, types$M.Dimension ], [ types$M.Ident, types$M.CDC ], [ types$M.Ident, types$M.LeftParenthesis ], [ types$M.AtKeyword, types$M.Ident ], [ types$M.AtKeyword, types$M.Function ], [ types$M.AtKeyword, types$M.Url ], [ types$M.AtKeyword, types$M.BadUrl ], [ types$M.AtKeyword, "-" ], [ types$M.AtKeyword, types$M.Number ], [ types$M.AtKeyword, types$M.Percentage ], [ types$M.AtKeyword, types$M.Dimension ], [ types$M.AtKeyword, types$M.CDC ], [ types$M.Hash, types$M.Ident ], [ types$M.Hash, types$M.Function ], [ types$M.Hash, types$M.Url ], [ types$M.Hash, types$M.BadUrl ], [ types$M.Hash, "-" ], [ types$M.Hash, types$M.Number ], [ types$M.Hash, types$M.Percentage ], [ types$M.Hash, types$M.Dimension ], [ types$M.Hash, types$M.CDC ], [ types$M.Dimension, types$M.Ident ], [ types$M.Dimension, types$M.Function ], [ types$M.Dimension, types$M.Url ], [ types$M.Dimension, types$M.BadUrl ], [ types$M.Dimension, "-" ], [ types$M.Dimension, types$M.Number ], [ types$M.Dimension, types$M.Percentage ], [ types$M.Dimension, types$M.Dimension ], [ types$M.Dimension, types$M.CDC ], [ "#", types$M.Ident ], [ "#", types$M.Function ], [ "#", types$M.Url ], [ "#", types$M.BadUrl ], [ "#", "-" ], [ "#", types$M.Number ], [ "#", types$M.Percentage ], [ "#", types$M.Dimension ], [ "#", types$M.CDC ], [ "-", types$M.Ident ], [ "-", types$M.Function ], [ "-", types$M.Url ], [ "-", types$M.BadUrl ], [ "-", "-" ], [ "-", types$M.Number ], [ "-", types$M.Percentage ], [ "-", types$M.Dimension ], [ "-", types$M.CDC ], [ types$M.Number, types$M.Ident ], [ types$M.Number, types$M.Function ], [ types$M.Number, types$M.Url ], [ types$M.Number, types$M.BadUrl ], [ types$M.Number, types$M.Number ], [ types$M.Number, types$M.Percentage ], [ types$M.Number, types$M.Dimension ], [ types$M.Number, "%" ], [ types$M.Number, types$M.CDC ], [ "@", types$M.Ident ], [ "@", types$M.Function ], [ "@", types$M.Url ], [ "@", types$M.BadUrl ], [ "@", "-" ], [ "@", types$M.CDC ], [ ".", types$M.Number ], [ ".", types$M.Percentage ], [ ".", types$M.Dimension ], [ "+", types$M.Number ], [ "+", types$M.Percentage ], [ "+", types$M.Dimension ], [ "/", "*" ] ], safePairs = specPairs.concat([ [ types$M.Ident, types$M.Hash ], [ types$M.Dimension, types$M.Hash ], [ types$M.Hash, types$M.Hash ], [ types$M.AtKeyword, types$M.LeftParenthesis ], [ types$M.AtKeyword, types$M.String ], [ types$M.AtKeyword, types$M.Colon ], [ types$M.Percentage, types$M.Percentage ], [ types$M.Percentage, types$M.Dimension ], [ types$M.Percentage, types$M.Function ], [ types$M.Percentage, "-" ], [ types$M.RightParenthesis, types$M.Ident ], [ types$M.RightParenthesis, types$M.Function ], [ types$M.RightParenthesis, types$M.Percentage ], [ types$M.RightParenthesis, types$M.Dimension ], [ types$M.RightParenthesis, types$M.Hash ], [ types$M.RightParenthesis, "-" ] ]);

function createMap(pairs) {
  const isWhiteSpaceRequired = new Set(pairs.map((([prev, next]) => code(prev) << 16 | code(next))));
  return function(prevCode, type, value) {
    const nextCode = code(type, value), nextCharCode = value.charCodeAt(0);
    return (45 === nextCharCode && type !== types$M.Ident && type !== types$M.Function && type !== types$M.CDC || 43 === nextCharCode ? isWhiteSpaceRequired.has(prevCode << 16 | nextCharCode << 8) : isWhiteSpaceRequired.has(prevCode << 16 | nextCode)) && this.emit(" ", types$M.WhiteSpace, !0), 
    nextCode;
  };
}

const spec = createMap(specPairs), safe$1 = createMap(safePairs);

tokenBefore$1.safe = safe$1, tokenBefore$1.spec = spec;

const index$a = tokenizer$2, sourceMap = sourceMap$1, tokenBefore = tokenBefore$1, types$L = types$R;

function processChildren(node, delimeter) {
  if ("function" != typeof delimeter) node.children.forEach(this.node, this); else {
    let prev = null;
    node.children.forEach((node => {
      null !== prev && delimeter.call(this, prev), this.node(node), prev = node;
    }));
  }
}

function processChunk(chunk) {
  index$a.tokenize(chunk, ((type, start, end) => {
    this.token(type, chunk.slice(start, end));
  }));
}

create$6.createGenerator = function(config) {
  const types$1 = new Map;
  for (let name in config.node) {
    const item = config.node[name];
    "function" == typeof (item.generate || item) && types$1.set(name, item.generate || item);
  }
  return function(node, options) {
    let buffer = "", prevCode = 0, handlers = {
      node(node) {
        if (!types$1.has(node.type)) throw new Error("Unknown node type: " + node.type);
        types$1.get(node.type).call(publicApi, node);
      },
      tokenBefore: tokenBefore.safe,
      token(type, value) {
        prevCode = this.tokenBefore(prevCode, type, value), this.emit(value, type, !1), 
        type === types$L.Delim && 92 === value.charCodeAt(0) && this.emit("\n", types$L.WhiteSpace, !0);
      },
      emit(value) {
        buffer += value;
      },
      result: () => buffer
    };
    options && ("function" == typeof options.decorator && (handlers = options.decorator(handlers)), 
    options.sourceMap && (handlers = sourceMap.generateSourceMap(handlers)), options.mode in tokenBefore && (handlers.tokenBefore = tokenBefore[options.mode]));
    const publicApi = {
      node: node => handlers.node(node),
      children: processChildren,
      token: (type, value) => handlers.token(type, value),
      tokenize: processChunk
    };
    return handlers.node(node), handlers.result();
  };
};

var create$5 = {};

const List$4 = List$7;

create$5.createConvertor = function(walk) {
  return {
    fromPlainObject: ast => (walk(ast, {
      enter(node) {
        node.children && node.children instanceof List$4.List == !1 && (node.children = (new List$4.List).fromArray(node.children));
      }
    }), ast),
    toPlainObject: ast => (walk(ast, {
      leave(node) {
        node.children && node.children instanceof List$4.List && (node.children = node.children.toArray());
      }
    }), ast)
  };
};

var create$4 = {};

const {hasOwnProperty: hasOwnProperty$a} = Object.prototype, noop$2 = function() {};

function ensureFunction$1(value) {
  return "function" == typeof value ? value : noop$2;
}

function invokeForType(fn, type) {
  return function(node, item, list) {
    node.type === type && fn.call(this, node, item, list);
  };
}

function getWalkersFromStructure(name, nodeType) {
  const structure = nodeType.structure, walkers = [];
  for (const key in structure) {
    if (!1 === hasOwnProperty$a.call(structure, key)) continue;
    let fieldTypes = structure[key];
    const walker = {
      name: key,
      type: !1,
      nullable: !1
    };
    Array.isArray(fieldTypes) || (fieldTypes = [ fieldTypes ]);
    for (const fieldType of fieldTypes) null === fieldType ? walker.nullable = !0 : "string" == typeof fieldType ? walker.type = "node" : Array.isArray(fieldType) && (walker.type = "list");
    walker.type && walkers.push(walker);
  }
  return walkers.length ? {
    context: nodeType.walkContext,
    fields: walkers
  } : null;
}

function createTypeIterator(config, reverse) {
  const fields = config.fields.slice(), contextName = config.context, useContext = "string" == typeof contextName;
  return reverse && fields.reverse(), function(node, context, walk, walkReducer) {
    let prevContextValue;
    useContext && (prevContextValue = context[contextName], context[contextName] = node);
    for (const field of fields) {
      const ref = node[field.name];
      if (!field.nullable || ref) if ("list" === field.type) {
        if (reverse ? ref.reduceRight(walkReducer, !1) : ref.reduce(walkReducer, !1)) return !0;
      } else if (walk(ref)) return !0;
    }
    useContext && (context[contextName] = prevContextValue);
  };
}

function createFastTraveralMap({StyleSheet, Atrule, Rule, Block, DeclarationList}) {
  return {
    Atrule: {
      StyleSheet,
      Atrule,
      Rule,
      Block
    },
    Rule: {
      StyleSheet,
      Atrule,
      Rule,
      Block
    },
    Declaration: {
      StyleSheet,
      Atrule,
      Rule,
      Block,
      DeclarationList
    }
  };
}

create$4.createWalker = function(config) {
  const types = function(config) {
    const types = {};
    for (const name in config.node) if (hasOwnProperty$a.call(config.node, name)) {
      const nodeType = config.node[name];
      if (!nodeType.structure) throw new Error("Missed `structure` field in `" + name + "` node type definition");
      types[name] = getWalkersFromStructure(0, nodeType);
    }
    return types;
  }(config), iteratorsNatural = {}, iteratorsReverse = {}, breakWalk = Symbol("break-walk"), skipNode = Symbol("skip-node");
  for (const name in types) hasOwnProperty$a.call(types, name) && null !== types[name] && (iteratorsNatural[name] = createTypeIterator(types[name], !1), 
  iteratorsReverse[name] = createTypeIterator(types[name], !0));
  const fastTraversalIteratorsNatural = createFastTraveralMap(iteratorsNatural), fastTraversalIteratorsReverse = createFastTraveralMap(iteratorsReverse), walk = function(root, options) {
    function walkNode(node, item, list) {
      const enterRet = enter.call(context, node, item, list);
      return enterRet === breakWalk || enterRet !== skipNode && (!(!iterators.hasOwnProperty(node.type) || !iterators[node.type](node, context, walkNode, walkReducer)) || leave.call(context, node, item, list) === breakWalk);
    }
    let enter = noop$2, leave = noop$2, iterators = iteratorsNatural, walkReducer = (ret, data, item, list) => ret || walkNode(data, item, list);
    const context = {
      break: breakWalk,
      skip: skipNode,
      root,
      stylesheet: null,
      atrule: null,
      atrulePrelude: null,
      rule: null,
      selector: null,
      block: null,
      declaration: null,
      function: null
    };
    if ("function" == typeof options) enter = options; else if (options && (enter = ensureFunction$1(options.enter), 
    leave = ensureFunction$1(options.leave), options.reverse && (iterators = iteratorsReverse), 
    options.visit)) {
      if (fastTraversalIteratorsNatural.hasOwnProperty(options.visit)) iterators = options.reverse ? fastTraversalIteratorsReverse[options.visit] : fastTraversalIteratorsNatural[options.visit]; else if (!types.hasOwnProperty(options.visit)) throw new Error("Bad value `" + options.visit + "` for `visit` option (should be: " + Object.keys(types).sort().join(", ") + ")");
      enter = invokeForType(enter, options.visit), leave = invokeForType(leave, options.visit);
    }
    if (enter === noop$2 && leave === noop$2) throw new Error("Neither `enter` nor `leave` walker handler is set or both aren't a function");
    walkNode(root);
  };
  return walk.break = breakWalk, walk.skip = skipNode, walk.find = function(ast, fn) {
    let found = null;
    return walk(ast, (function(node, item, list) {
      if (fn.call(this, node, item, list)) return found = node, breakWalk;
    })), found;
  }, walk.findLast = function(ast, fn) {
    let found = null;
    return walk(ast, {
      reverse: !0,
      enter(node, item, list) {
        if (fn.call(this, node, item, list)) return found = node, breakWalk;
      }
    }), found;
  }, walk.findAll = function(ast, fn) {
    const found = [];
    return walk(ast, (function(node, item, list) {
      fn.call(this, node, item, list) && found.push(node);
    })), found;
  }, walk;
};

var Lexer$3 = {}, error$2 = {}, generate$L = {};

function noop$1(value) {
  return value;
}

function internalGenerate(node, decorate, forceBraces, compact) {
  let result;
  switch (node.type) {
   case "Group":
    result = function(node, decorate, forceBraces, compact) {
      const combinator = " " === node.combinator || compact ? node.combinator : " " + node.combinator + " ", result = node.terms.map((term => internalGenerate(term, decorate, forceBraces, compact))).join(combinator);
      return node.explicit || forceBraces ? (compact || "," === result[0] ? "[" : "[ ") + result + (compact ? "]" : " ]") : result;
    }(node, decorate, forceBraces, compact) + (node.disallowEmpty ? "!" : "");
    break;

   case "Multiplier":
    return internalGenerate(node.term, decorate, forceBraces, compact) + decorate(function(multiplier) {
      const {min, max, comma} = multiplier;
      return 0 === min && 0 === max ? comma ? "#?" : "*" : 0 === min && 1 === max ? "?" : 1 === min && 0 === max ? comma ? "#" : "+" : 1 === min && 1 === max ? "" : (comma ? "#" : "") + (min === max ? "{" + min + "}" : "{" + min + "," + (0 !== max ? max : "") + "}");
    }(node), node);

   case "Type":
    result = "<" + node.name + (node.opts ? decorate(function(node) {
      if ("Range" === node.type) return " [" + (null === node.min ? "-∞" : node.min) + "," + (null === node.max ? "∞" : node.max) + "]";
      throw new Error("Unknown node type `" + node.type + "`");
    }(node.opts), node.opts) : "") + ">";
    break;

   case "Property":
    result = "<'" + node.name + "'>";
    break;

   case "Keyword":
    result = node.name;
    break;

   case "AtKeyword":
    result = "@" + node.name;
    break;

   case "Function":
    result = node.name + "(";
    break;

   case "String":
   case "Token":
    result = node.value;
    break;

   case "Comma":
    result = ",";
    break;

   default:
    throw new Error("Unknown node type `" + node.type + "`");
  }
  return decorate(result, node);
}

generate$L.generate = function(node, options) {
  let decorate = noop$1, forceBraces = !1, compact = !1;
  return "function" == typeof options ? decorate = options : options && (forceBraces = Boolean(options.forceBraces), 
  compact = Boolean(options.compact), "function" == typeof options.decorate && (decorate = options.decorate)), 
  internalGenerate(node, decorate, forceBraces, compact);
};

const createCustomError$1 = createCustomError$4, generate$J = generate$L, defaultLoc = {
  offset: 0,
  line: 1,
  column: 1
};

function fromLoc(node, point) {
  const value = node && node.loc && node.loc[point];
  return value ? "line" in value ? buildLoc(value) : value : null;
}

function buildLoc({offset, line, column}, extra) {
  const loc = {
    offset,
    line,
    column
  };
  if (extra) {
    const lines = extra.split(/\n|\r\n?|\f/);
    loc.offset += extra.length, loc.line += lines.length - 1, loc.column = 1 === lines.length ? loc.column + extra.length : lines.pop().length + 1;
  }
  return loc;
}

error$2.SyntaxMatchError = function(message, syntax, node, matchResult) {
  const error = createCustomError$1.createCustomError("SyntaxMatchError", message), {css, mismatchOffset, mismatchLength, start, end} = function(matchResult, node) {
    const tokens = matchResult.tokens, longestMatch = matchResult.longestMatch, mismatchNode = longestMatch < tokens.length && tokens[longestMatch].node || null, badNode = mismatchNode !== node ? mismatchNode : null;
    let start, end, mismatchOffset = 0, mismatchLength = 0, entries = 0, css = "";
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i].value;
      i === longestMatch && (mismatchLength = token.length, mismatchOffset = css.length), 
      null !== badNode && tokens[i].node === badNode && (i <= longestMatch ? entries++ : entries = 0), 
      css += token;
    }
    return longestMatch === tokens.length || entries > 1 ? (start = fromLoc(badNode || node, "end") || buildLoc(defaultLoc, css), 
    end = buildLoc(start)) : (start = fromLoc(badNode, "start") || buildLoc(fromLoc(node, "start") || defaultLoc, css.slice(0, mismatchOffset)), 
    end = fromLoc(badNode, "end") || buildLoc(start, css.substr(mismatchOffset, mismatchLength))), 
    {
      css,
      mismatchOffset,
      mismatchLength,
      start,
      end
    };
  }(matchResult, node);
  return error.rawMessage = message, error.syntax = syntax ? generate$J.generate(syntax) : "<generic>", 
  error.css = css, error.mismatchOffset = mismatchOffset, error.mismatchLength = mismatchLength, 
  error.message = message + "\n  syntax: " + error.syntax + "\n   value: " + (css || "<empty string>") + "\n  --------" + new Array(error.mismatchOffset + 1).join("-") + "^", 
  Object.assign(error, start), error.loc = {
    source: node && node.loc && node.loc.source || "<unknown>",
    start,
    end
  }, error;
}, error$2.SyntaxReferenceError = function(type, referenceName) {
  const error = createCustomError$1.createCustomError("SyntaxReferenceError", type + (referenceName ? " `" + referenceName + "`" : ""));
  return error.reference = referenceName, error;
};

var names$4 = {};

const keywords = new Map, properties = new Map, HYPHENMINUS$5 = 45, keyword = function(keyword) {
  if (keywords.has(keyword)) return keywords.get(keyword);
  const name = keyword.toLowerCase();
  let descriptor = keywords.get(name);
  if (void 0 === descriptor) {
    const custom = isCustomProperty(name, 0), vendor = custom ? "" : getVendorPrefix(name, 0);
    descriptor = Object.freeze({
      basename: name.substr(vendor.length),
      name,
      prefix: vendor,
      vendor,
      custom
    });
  }
  return keywords.set(keyword, descriptor), descriptor;
}, property = function(property) {
  if (properties.has(property)) return properties.get(property);
  let name = property, hack = property[0];
  "/" === hack ? hack = "/" === property[1] ? "//" : "/" : "_" !== hack && "*" !== hack && "$" !== hack && "#" !== hack && "+" !== hack && "&" !== hack && (hack = "");
  const custom = isCustomProperty(name, hack.length);
  if (!custom && (name = name.toLowerCase(), properties.has(name))) {
    const descriptor = properties.get(name);
    return properties.set(property, descriptor), descriptor;
  }
  const vendor = custom ? "" : getVendorPrefix(name, hack.length), prefix = name.substr(0, hack.length + vendor.length), descriptor = Object.freeze({
    basename: name.substr(prefix.length),
    name: name.substr(hack.length),
    hack,
    vendor,
    prefix,
    custom
  });
  return properties.set(property, descriptor), descriptor;
}, vendorPrefix = getVendorPrefix;

function isCustomProperty(str, offset) {
  return offset = offset || 0, str.length - offset >= 2 && str.charCodeAt(offset) === HYPHENMINUS$5 && str.charCodeAt(offset + 1) === HYPHENMINUS$5;
}

function getVendorPrefix(str, offset) {
  if (offset = offset || 0, str.length - offset >= 3 && str.charCodeAt(offset) === HYPHENMINUS$5 && str.charCodeAt(offset + 1) !== HYPHENMINUS$5) {
    const secondDashIndex = str.indexOf("-", offset + 2);
    if (-1 !== secondDashIndex) return str.substring(offset, secondDashIndex + 1);
  }
  return "";
}

names$4.isCustomProperty = isCustomProperty, names$4.keyword = keyword, names$4.property = property, 
names$4.vendorPrefix = vendorPrefix;

var genericConst$2 = {};

genericConst$2.cssWideKeywords = [ "initial", "inherit", "unset", "revert", "revert-layer" ];

const charCodeDefinitions$8 = charCodeDefinitions$c, types$K = types$R, utils$g = utils$k, PLUSSIGN$8 = 0x002B, HYPHENMINUS$4 = 0x002D;

function isDelim$1(token, code) {
  return null !== token && token.type === types$K.Delim && token.value.charCodeAt(0) === code;
}

function skipSC(token, offset, getNextToken) {
  for (;null !== token && (token.type === types$K.WhiteSpace || token.type === types$K.Comment); ) token = getNextToken(++offset);
  return offset;
}

function checkInteger$1(token, valueOffset, disallowSign, offset) {
  if (!token) return 0;
  const code = token.value.charCodeAt(valueOffset);
  if (code === PLUSSIGN$8 || code === HYPHENMINUS$4) {
    if (disallowSign) return 0;
    valueOffset++;
  }
  for (;valueOffset < token.value.length; valueOffset++) if (!charCodeDefinitions$8.isDigit(token.value.charCodeAt(valueOffset))) return 0;
  return offset + 1;
}

function consumeB$1(token, offset_, getNextToken) {
  let sign = !1, offset = skipSC(token, offset_, getNextToken);
  if (null === (token = getNextToken(offset))) return offset_;
  if (token.type !== types$K.Number) {
    if (!isDelim$1(token, PLUSSIGN$8) && !isDelim$1(token, HYPHENMINUS$4)) return offset_;
    if (sign = !0, offset = skipSC(getNextToken(++offset), offset, getNextToken), null === (token = getNextToken(offset)) || token.type !== types$K.Number) return 0;
  }
  if (!sign) {
    const code = token.value.charCodeAt(0);
    if (code !== PLUSSIGN$8 && code !== HYPHENMINUS$4) return 0;
  }
  return checkInteger$1(token, sign ? 0 : 1, sign, offset);
}

var genericAnPlusB$1 = function(token, getNextToken) {
  let offset = 0;
  if (!token) return 0;
  if (token.type === types$K.Number) return checkInteger$1(token, 0, false, offset);
  if (token.type === types$K.Ident && token.value.charCodeAt(0) === HYPHENMINUS$4) {
    if (!utils$g.cmpChar(token.value, 1, 110)) return 0;
    switch (token.value.length) {
     case 2:
      return consumeB$1(getNextToken(++offset), offset, getNextToken);

     case 3:
      return token.value.charCodeAt(2) !== HYPHENMINUS$4 ? 0 : (offset = skipSC(getNextToken(++offset), offset, getNextToken), 
      checkInteger$1(token = getNextToken(offset), 0, true, offset));

     default:
      return token.value.charCodeAt(2) !== HYPHENMINUS$4 ? 0 : checkInteger$1(token, 3, true, offset);
    }
  } else if (token.type === types$K.Ident || isDelim$1(token, PLUSSIGN$8) && getNextToken(offset + 1).type === types$K.Ident) {
    if (token.type !== types$K.Ident && (token = getNextToken(++offset)), null === token || !utils$g.cmpChar(token.value, 0, 110)) return 0;
    switch (token.value.length) {
     case 1:
      return consumeB$1(getNextToken(++offset), offset, getNextToken);

     case 2:
      return token.value.charCodeAt(1) !== HYPHENMINUS$4 ? 0 : (offset = skipSC(getNextToken(++offset), offset, getNextToken), 
      checkInteger$1(token = getNextToken(offset), 0, true, offset));

     default:
      return token.value.charCodeAt(1) !== HYPHENMINUS$4 ? 0 : checkInteger$1(token, 2, true, offset);
    }
  } else if (token.type === types$K.Dimension) {
    let code = token.value.charCodeAt(0), sign = code === PLUSSIGN$8 || code === HYPHENMINUS$4 ? 1 : 0, i = sign;
    for (;i < token.value.length && charCodeDefinitions$8.isDigit(token.value.charCodeAt(i)); i++) ;
    return i === sign ? 0 : utils$g.cmpChar(token.value, i, 110) ? i + 1 === token.value.length ? consumeB$1(getNextToken(++offset), offset, getNextToken) : token.value.charCodeAt(i + 1) !== HYPHENMINUS$4 ? 0 : i + 2 === token.value.length ? (offset = skipSC(getNextToken(++offset), offset, getNextToken), 
    checkInteger$1(token = getNextToken(offset), 0, true, offset)) : checkInteger$1(token, i + 2, true, offset) : 0;
  }
  return 0;
};

const charCodeDefinitions$7 = charCodeDefinitions$c, types$J = types$R, utils$f = utils$k, HYPHENMINUS$3 = 0x002D, QUESTIONMARK$2 = 0x003F;

function isDelim(token, code) {
  return null !== token && token.type === types$J.Delim && token.value.charCodeAt(0) === code;
}

function hexSequence(token, offset, allowDash) {
  let hexlen = 0;
  for (let pos = offset; pos < token.value.length; pos++) {
    const code = token.value.charCodeAt(pos);
    if (code === HYPHENMINUS$3 && allowDash && 0 !== hexlen) return hexSequence(token, offset + hexlen + 1, !1), 
    6;
    if (!charCodeDefinitions$7.isHexDigit(code)) return 0;
    if (++hexlen > 6) return 0;
  }
  return hexlen;
}

function withQuestionMarkSequence(consumed, length, getNextToken) {
  if (!consumed) return 0;
  for (;isDelim(getNextToken(length), QUESTIONMARK$2); ) {
    if (++consumed > 6) return 0;
    length++;
  }
  return length;
}

var genericUrange$1 = function(token, getNextToken) {
  let length = 0;
  if (null === token || token.type !== types$J.Ident || !utils$f.cmpChar(token.value, 0, 117)) return 0;
  if (null === (token = getNextToken(++length))) return 0;
  if (isDelim(token, 43)) return null === (token = getNextToken(++length)) ? 0 : token.type === types$J.Ident ? withQuestionMarkSequence(hexSequence(token, 0, !0), ++length, getNextToken) : isDelim(token, QUESTIONMARK$2) ? withQuestionMarkSequence(1, ++length, getNextToken) : 0;
  if (token.type === types$J.Number) {
    const consumedHexLength = hexSequence(token, 1, !0);
    return 0 === consumedHexLength ? 0 : null === (token = getNextToken(++length)) ? length : token.type === types$J.Dimension || token.type === types$J.Number ? function(token, code) {
      return token.value.charCodeAt(0) === code;
    }(token, HYPHENMINUS$3) && hexSequence(token, 1, !1) ? length + 1 : 0 : withQuestionMarkSequence(consumedHexLength, length, getNextToken);
  }
  return token.type === types$J.Dimension ? withQuestionMarkSequence(hexSequence(token, 1, !0), ++length, getNextToken) : 0;
};

const genericConst$1 = genericConst$2, genericAnPlusB = genericAnPlusB$1, genericUrange = genericUrange$1, types$I = types$R, charCodeDefinitions$6 = charCodeDefinitions$c, utils$e = utils$k, calcFunctionNames = [ "calc(", "-moz-calc(", "-webkit-calc(" ], balancePair = new Map([ [ types$I.Function, types$I.RightParenthesis ], [ types$I.LeftParenthesis, types$I.RightParenthesis ], [ types$I.LeftSquareBracket, types$I.RightSquareBracket ], [ types$I.LeftCurlyBracket, types$I.RightCurlyBracket ] ]);

function charCodeAt(str, index) {
  return index < str.length ? str.charCodeAt(index) : 0;
}

function eqStr(actual, expected) {
  return utils$e.cmpStr(actual, 0, actual.length, expected);
}

function eqStrAny(actual, expected) {
  for (let i = 0; i < expected.length; i++) if (eqStr(actual, expected[i])) return !0;
  return !1;
}

function isPostfixIeHack(str, offset) {
  return offset === str.length - 2 && (0x005C === charCodeAt(str, offset) && charCodeDefinitions$6.isDigit(charCodeAt(str, offset + 1)));
}

function outOfRange(opts, value, numEnd) {
  if (opts && "Range" === opts.type) {
    const num = Number(void 0 !== numEnd && numEnd !== value.length ? value.substr(0, numEnd) : value);
    if (isNaN(num)) return !0;
    if (null !== opts.min && num < opts.min && "string" != typeof opts.min) return !0;
    if (null !== opts.max && num > opts.max && "string" != typeof opts.max) return !0;
  }
  return !1;
}

function calc(next) {
  return function(token, getNextToken, opts) {
    return null === token ? 0 : token.type === types$I.Function && eqStrAny(token.value, calcFunctionNames) ? function(token, getNextToken) {
      let balanceCloseType = 0, balanceStash = [], length = 0;
      scan: do {
        switch (token.type) {
         case types$I.RightCurlyBracket:
         case types$I.RightParenthesis:
         case types$I.RightSquareBracket:
          if (token.type !== balanceCloseType) break scan;
          if (balanceCloseType = balanceStash.pop(), 0 === balanceStash.length) {
            length++;
            break scan;
          }
          break;

         case types$I.Function:
         case types$I.LeftParenthesis:
         case types$I.LeftSquareBracket:
         case types$I.LeftCurlyBracket:
          balanceStash.push(balanceCloseType), balanceCloseType = balancePair.get(token.type);
        }
        length++;
      } while (token = getNextToken(length));
      return length;
    }(token, getNextToken) : next(token, getNextToken, opts);
  };
}

function tokenType(expectedTokenType) {
  return function(token) {
    return null === token || token.type !== expectedTokenType ? 0 : 1;
  };
}

function dimension(type) {
  return type && (type = new Set(type)), function(token, getNextToken, opts) {
    if (null === token || token.type !== types$I.Dimension) return 0;
    const numberEnd = utils$e.consumeNumber(token.value, 0);
    if (null !== type) {
      const reverseSolidusOffset = token.value.indexOf("\\", numberEnd), unit = -1 !== reverseSolidusOffset && isPostfixIeHack(token.value, reverseSolidusOffset) ? token.value.substring(numberEnd, reverseSolidusOffset) : token.value.substr(numberEnd);
      if (!1 === type.has(unit.toLowerCase())) return 0;
    }
    return outOfRange(opts, token.value, numberEnd) ? 0 : 1;
  };
}

function zero(next) {
  return "function" != typeof next && (next = function() {
    return 0;
  }), function(token, getNextToken, opts) {
    return null !== token && token.type === types$I.Number && 0 === Number(token.value) ? 1 : next(token, getNextToken, opts);
  };
}

const genericSyntaxes = {
  "ident-token": tokenType(types$I.Ident),
  "function-token": tokenType(types$I.Function),
  "at-keyword-token": tokenType(types$I.AtKeyword),
  "hash-token": tokenType(types$I.Hash),
  "string-token": tokenType(types$I.String),
  "bad-string-token": tokenType(types$I.BadString),
  "url-token": tokenType(types$I.Url),
  "bad-url-token": tokenType(types$I.BadUrl),
  "delim-token": tokenType(types$I.Delim),
  "number-token": tokenType(types$I.Number),
  "percentage-token": tokenType(types$I.Percentage),
  "dimension-token": tokenType(types$I.Dimension),
  "whitespace-token": tokenType(types$I.WhiteSpace),
  "CDO-token": tokenType(types$I.CDO),
  "CDC-token": tokenType(types$I.CDC),
  "colon-token": tokenType(types$I.Colon),
  "semicolon-token": tokenType(types$I.Semicolon),
  "comma-token": tokenType(types$I.Comma),
  "[-token": tokenType(types$I.LeftSquareBracket),
  "]-token": tokenType(types$I.RightSquareBracket),
  "(-token": tokenType(types$I.LeftParenthesis),
  ")-token": tokenType(types$I.RightParenthesis),
  "{-token": tokenType(types$I.LeftCurlyBracket),
  "}-token": tokenType(types$I.RightCurlyBracket),
  "string": tokenType(types$I.String),
  "ident": tokenType(types$I.Ident),
  "custom-ident": function(token) {
    if (null === token || token.type !== types$I.Ident) return 0;
    const name = token.value.toLowerCase();
    return eqStrAny(name, genericConst$1.cssWideKeywords) || eqStr(name, "default") ? 0 : 1;
  },
  "custom-property-name": function(token) {
    return null === token || token.type !== types$I.Ident || 0x002D !== charCodeAt(token.value, 0) || 0x002D !== charCodeAt(token.value, 1) ? 0 : 1;
  },
  "hex-color": function(token) {
    if (null === token || token.type !== types$I.Hash) return 0;
    const length = token.value.length;
    if (4 !== length && 5 !== length && 7 !== length && 9 !== length) return 0;
    for (let i = 1; i < length; i++) if (!charCodeDefinitions$6.isHexDigit(charCodeAt(token.value, i))) return 0;
    return 1;
  },
  "id-selector": function(token) {
    return null === token || token.type !== types$I.Hash ? 0 : charCodeDefinitions$6.isIdentifierStart(charCodeAt(token.value, 1), charCodeAt(token.value, 2), charCodeAt(token.value, 3)) ? 1 : 0;
  },
  "an-plus-b": genericAnPlusB,
  "urange": genericUrange,
  "declaration-value": function(token, getNextToken) {
    if (!token) return 0;
    let balanceCloseType = 0, balanceStash = [], length = 0;
    scan: do {
      switch (token.type) {
       case types$I.BadString:
       case types$I.BadUrl:
        break scan;

       case types$I.RightCurlyBracket:
       case types$I.RightParenthesis:
       case types$I.RightSquareBracket:
        if (token.type !== balanceCloseType) break scan;
        balanceCloseType = balanceStash.pop();
        break;

       case types$I.Semicolon:
        if (0 === balanceCloseType) break scan;
        break;

       case types$I.Delim:
        if (0 === balanceCloseType && "!" === token.value) break scan;
        break;

       case types$I.Function:
       case types$I.LeftParenthesis:
       case types$I.LeftSquareBracket:
       case types$I.LeftCurlyBracket:
        balanceStash.push(balanceCloseType), balanceCloseType = balancePair.get(token.type);
      }
      length++;
    } while (token = getNextToken(length));
    return length;
  },
  "any-value": function(token, getNextToken) {
    if (!token) return 0;
    let balanceCloseType = 0, balanceStash = [], length = 0;
    scan: do {
      switch (token.type) {
       case types$I.BadString:
       case types$I.BadUrl:
        break scan;

       case types$I.RightCurlyBracket:
       case types$I.RightParenthesis:
       case types$I.RightSquareBracket:
        if (token.type !== balanceCloseType) break scan;
        balanceCloseType = balanceStash.pop();
        break;

       case types$I.Function:
       case types$I.LeftParenthesis:
       case types$I.LeftSquareBracket:
       case types$I.LeftCurlyBracket:
        balanceStash.push(balanceCloseType), balanceCloseType = balancePair.get(token.type);
      }
      length++;
    } while (token = getNextToken(length));
    return length;
  },
  "dimension": calc(dimension(null)),
  "angle": calc(dimension([ "deg", "grad", "rad", "turn" ])),
  "decibel": calc(dimension([ "db" ])),
  "frequency": calc(dimension([ "hz", "khz" ])),
  "flex": calc(dimension([ "fr" ])),
  "length": calc(zero(dimension([ "cm", "mm", "q", "in", "pt", "pc", "px", "em", "rem", "ex", "rex", "cap", "rcap", "ch", "rch", "ic", "ric", "lh", "rlh", "vw", "svw", "lvw", "dvw", "vh", "svh", "lvh", "dvh", "vi", "svi", "lvi", "dvi", "vb", "svb", "lvb", "dvb", "vmin", "svmin", "lvmin", "dvmin", "vmax", "svmax", "lvmax", "dvmax", "cqw", "cqh", "cqi", "cqb", "cqmin", "cqmax" ]))),
  "resolution": calc(dimension([ "dpi", "dpcm", "dppx", "x" ])),
  "semitones": calc(dimension([ "st" ])),
  "time": calc(dimension([ "s", "ms" ])),
  "percentage": calc((function(token, getNextToken, opts) {
    return null === token || token.type !== types$I.Percentage || outOfRange(opts, token.value, token.value.length - 1) ? 0 : 1;
  })),
  "zero": zero(),
  "number": calc((function(token, getNextToken, opts) {
    if (null === token) return 0;
    const numberEnd = utils$e.consumeNumber(token.value, 0);
    return numberEnd === token.value.length || isPostfixIeHack(token.value, numberEnd) ? outOfRange(opts, token.value, numberEnd) ? 0 : 1 : 0;
  })),
  "integer": calc((function(token, getNextToken, opts) {
    if (null === token || token.type !== types$I.Number) return 0;
    let i = 0x002B === charCodeAt(token.value, 0) || 0x002D === charCodeAt(token.value, 0) ? 1 : 0;
    for (;i < token.value.length; i++) if (!charCodeDefinitions$6.isDigit(charCodeAt(token.value, i))) return 0;
    return outOfRange(opts, token.value, i) ? 0 : 1;
  }))
};

var generic$1 = genericSyntaxes;

const index$9 = tokenizer$2, astToTokens = {
  decorator(handlers) {
    const tokens = [];
    let curNode = null;
    return {
      ...handlers,
      node(node) {
        const tmp = curNode;
        curNode = node, handlers.node.call(this, node), curNode = tmp;
      },
      emit(value, type, auto) {
        tokens.push({
          type,
          value,
          node: auto ? null : curNode
        });
      },
      result: () => tokens
    };
  }
};

var prepareTokens_1 = function(value, syntax) {
  return "string" == typeof value ? function(str) {
    const tokens = [];
    return index$9.tokenize(str, ((type, start, end) => tokens.push({
      type,
      value: str.slice(start, end),
      node: null
    }))), tokens;
  }(value) : syntax.generate(value, astToTokens);
}, matchGraph$2 = {}, parse$L = {}, tokenizer$1 = {}, _SyntaxError = {};

const createCustomError = createCustomError$4;

_SyntaxError.SyntaxError = function(message, input, offset) {
  return Object.assign(createCustomError.createCustomError("SyntaxError", message), {
    input,
    offset,
    rawMessage: message,
    message: message + "\n  " + input + "\n--" + new Array((offset || input.length) + 1).join("-") + "^"
  });
};

const SyntaxError$2 = _SyntaxError;

tokenizer$1.Tokenizer = class {
  constructor(str) {
    this.str = str, this.pos = 0;
  }
  charCodeAt(pos) {
    return pos < this.str.length ? this.str.charCodeAt(pos) : 0;
  }
  charCode() {
    return this.charCodeAt(this.pos);
  }
  nextCharCode() {
    return this.charCodeAt(this.pos + 1);
  }
  nextNonWsCode(pos) {
    return this.charCodeAt(this.findWsEnd(pos));
  }
  findWsEnd(pos) {
    for (;pos < this.str.length; pos++) {
      const code = this.str.charCodeAt(pos);
      if (13 !== code && 10 !== code && 12 !== code && 32 !== code && 9 !== code) break;
    }
    return pos;
  }
  substringToPos(end) {
    return this.str.substring(this.pos, this.pos = end);
  }
  eat(code) {
    this.charCode() !== code && this.error("Expect `" + String.fromCharCode(code) + "`"), 
    this.pos++;
  }
  peek() {
    return this.pos < this.str.length ? this.str.charAt(this.pos++) : "";
  }
  error(message) {
    throw new SyntaxError$2.SyntaxError(message, this.str, this.pos);
  }
};

const tokenizer = tokenizer$1, TAB = 9, N$1 = 10, F = 12, R = 13, SPACE$2 = 32, EXCLAMATIONMARK$2 = 33, NUMBERSIGN$3 = 35, AMPERSAND$1 = 38, APOSTROPHE$2 = 39, LEFTPARENTHESIS$2 = 40, RIGHTPARENTHESIS$2 = 41, ASTERISK$6 = 42, PLUSSIGN$6 = 43, COMMA = 44, HYPERMINUS = 45, LESSTHANSIGN = 60, GREATERTHANSIGN$2 = 62, QUESTIONMARK$1 = 63, COMMERCIALAT = 64, LEFTSQUAREBRACKET = 91, RIGHTSQUAREBRACKET = 93, LEFTCURLYBRACKET = 123, VERTICALLINE$3 = 124, RIGHTCURLYBRACKET = 125, INFINITY = 8734, NAME_CHAR = new Uint8Array(128).map(((_, idx) => /[a-zA-Z0-9\-]/.test(String.fromCharCode(idx)) ? 1 : 0)), COMBINATOR_PRECEDENCE = {
  " ": 1,
  "&&": 2,
  "||": 3,
  "|": 4
};

function scanSpaces(tokenizer) {
  return tokenizer.substringToPos(tokenizer.findWsEnd(tokenizer.pos));
}

function scanWord(tokenizer) {
  let end = tokenizer.pos;
  for (;end < tokenizer.str.length; end++) {
    const code = tokenizer.str.charCodeAt(end);
    if (code >= 128 || 0 === NAME_CHAR[code]) break;
  }
  return tokenizer.pos === end && tokenizer.error("Expect a keyword"), tokenizer.substringToPos(end);
}

function scanNumber(tokenizer) {
  let end = tokenizer.pos;
  for (;end < tokenizer.str.length; end++) {
    const code = tokenizer.str.charCodeAt(end);
    if (code < 48 || code > 57) break;
  }
  return tokenizer.pos === end && tokenizer.error("Expect a number"), tokenizer.substringToPos(end);
}

function scanString(tokenizer) {
  const end = tokenizer.str.indexOf("'", tokenizer.pos + 1);
  return -1 === end && (tokenizer.pos = tokenizer.str.length, tokenizer.error("Expect an apostrophe")), 
  tokenizer.substringToPos(end + 1);
}

function readMultiplierRange(tokenizer) {
  let min = null, max = null;
  return tokenizer.eat(LEFTCURLYBRACKET), min = scanNumber(tokenizer), tokenizer.charCode() === COMMA ? (tokenizer.pos++, 
  tokenizer.charCode() !== RIGHTCURLYBRACKET && (max = scanNumber(tokenizer))) : max = min, 
  tokenizer.eat(RIGHTCURLYBRACKET), {
    min: Number(min),
    max: max ? Number(max) : 0
  };
}

function maybeMultiplied(tokenizer, node) {
  const multiplier = function(tokenizer) {
    let range = null, comma = !1;
    switch (tokenizer.charCode()) {
     case ASTERISK$6:
      tokenizer.pos++, range = {
        min: 0,
        max: 0
      };
      break;

     case PLUSSIGN$6:
      tokenizer.pos++, range = {
        min: 1,
        max: 0
      };
      break;

     case QUESTIONMARK$1:
      tokenizer.pos++, range = {
        min: 0,
        max: 1
      };
      break;

     case NUMBERSIGN$3:
      tokenizer.pos++, comma = !0, tokenizer.charCode() === LEFTCURLYBRACKET ? range = readMultiplierRange(tokenizer) : tokenizer.charCode() === QUESTIONMARK$1 ? (tokenizer.pos++, 
      range = {
        min: 0,
        max: 0
      }) : range = {
        min: 1,
        max: 0
      };
      break;

     case LEFTCURLYBRACKET:
      range = readMultiplierRange(tokenizer);
      break;

     default:
      return null;
    }
    return {
      type: "Multiplier",
      comma,
      min: range.min,
      max: range.max,
      term: null
    };
  }(tokenizer);
  return null !== multiplier ? (multiplier.term = node, tokenizer.charCode() === NUMBERSIGN$3 && tokenizer.charCodeAt(tokenizer.pos - 1) === PLUSSIGN$6 ? maybeMultiplied(tokenizer, multiplier) : multiplier) : node;
}

function maybeToken(tokenizer) {
  const ch = tokenizer.peek();
  return "" === ch ? null : {
    type: "Token",
    value: ch
  };
}

function readType(tokenizer) {
  let name, opts = null;
  return tokenizer.eat(LESSTHANSIGN), name = scanWord(tokenizer), tokenizer.charCode() === LEFTPARENTHESIS$2 && tokenizer.nextCharCode() === RIGHTPARENTHESIS$2 && (tokenizer.pos += 2, 
  name += "()"), tokenizer.charCodeAt(tokenizer.findWsEnd(tokenizer.pos)) === LEFTSQUAREBRACKET && (scanSpaces(tokenizer), 
  opts = function(tokenizer) {
    let min = null, max = null, sign = 1;
    return tokenizer.eat(LEFTSQUAREBRACKET), tokenizer.charCode() === HYPERMINUS && (tokenizer.peek(), 
    sign = -1), -1 == sign && tokenizer.charCode() === INFINITY ? tokenizer.peek() : (min = sign * Number(scanNumber(tokenizer)), 
    0 !== NAME_CHAR[tokenizer.charCode()] && (min += scanWord(tokenizer))), scanSpaces(tokenizer), 
    tokenizer.eat(COMMA), scanSpaces(tokenizer), tokenizer.charCode() === INFINITY ? tokenizer.peek() : (sign = 1, 
    tokenizer.charCode() === HYPERMINUS && (tokenizer.peek(), sign = -1), max = sign * Number(scanNumber(tokenizer)), 
    0 !== NAME_CHAR[tokenizer.charCode()] && (max += scanWord(tokenizer))), tokenizer.eat(RIGHTSQUAREBRACKET), 
    {
      type: "Range",
      min,
      max
    };
  }(tokenizer)), tokenizer.eat(GREATERTHANSIGN$2), maybeMultiplied(tokenizer, {
    type: "Type",
    name,
    opts
  });
}

function regroupTerms(terms, combinators) {
  function createGroup(terms, combinator) {
    return {
      type: "Group",
      terms,
      combinator,
      disallowEmpty: !1,
      explicit: !1
    };
  }
  let combinator;
  for (combinators = Object.keys(combinators).sort(((a, b) => COMBINATOR_PRECEDENCE[a] - COMBINATOR_PRECEDENCE[b])); combinators.length > 0; ) {
    combinator = combinators.shift();
    let i = 0, subgroupStart = 0;
    for (;i < terms.length; i++) {
      const term = terms[i];
      "Combinator" === term.type && (term.value === combinator ? (-1 === subgroupStart && (subgroupStart = i - 1), 
      terms.splice(i, 1), i--) : (-1 !== subgroupStart && i - subgroupStart > 1 && (terms.splice(subgroupStart, i - subgroupStart, createGroup(terms.slice(subgroupStart, i), combinator)), 
      i = subgroupStart + 1), subgroupStart = -1));
    }
    -1 !== subgroupStart && combinators.length && terms.splice(subgroupStart, i - subgroupStart, createGroup(terms.slice(subgroupStart, i), combinator));
  }
  return combinator;
}

function readImplicitGroup(tokenizer) {
  const terms = [], combinators = {};
  let token, prevToken = null, prevTokenPos = tokenizer.pos;
  for (;token = peek(tokenizer); ) "Spaces" !== token.type && ("Combinator" === token.type ? (null !== prevToken && "Combinator" !== prevToken.type || (tokenizer.pos = prevTokenPos, 
  tokenizer.error("Unexpected combinator")), combinators[token.value] = !0) : null !== prevToken && "Combinator" !== prevToken.type && (combinators[" "] = !0, 
  terms.push({
    type: "Combinator",
    value: " "
  })), terms.push(token), prevToken = token, prevTokenPos = tokenizer.pos);
  return null !== prevToken && "Combinator" === prevToken.type && (tokenizer.pos -= prevTokenPos, 
  tokenizer.error("Unexpected combinator")), {
    type: "Group",
    terms,
    combinator: regroupTerms(terms, combinators) || " ",
    disallowEmpty: !1,
    explicit: !1
  };
}

function peek(tokenizer) {
  let code = tokenizer.charCode();
  if (code < 128 && 1 === NAME_CHAR[code]) return function(tokenizer) {
    const name = scanWord(tokenizer);
    return tokenizer.charCode() === LEFTPARENTHESIS$2 ? (tokenizer.pos++, {
      type: "Function",
      name
    }) : maybeMultiplied(tokenizer, {
      type: "Keyword",
      name
    });
  }(tokenizer);
  switch (code) {
   case RIGHTSQUAREBRACKET:
    break;

   case LEFTSQUAREBRACKET:
    return maybeMultiplied(tokenizer, function(tokenizer) {
      let result;
      return tokenizer.eat(LEFTSQUAREBRACKET), result = readImplicitGroup(tokenizer), 
      tokenizer.eat(RIGHTSQUAREBRACKET), result.explicit = !0, tokenizer.charCode() === EXCLAMATIONMARK$2 && (tokenizer.pos++, 
      result.disallowEmpty = !0), result;
    }(tokenizer));

   case LESSTHANSIGN:
    return tokenizer.nextCharCode() === APOSTROPHE$2 ? function(tokenizer) {
      let name;
      return tokenizer.eat(LESSTHANSIGN), tokenizer.eat(APOSTROPHE$2), name = scanWord(tokenizer), 
      tokenizer.eat(APOSTROPHE$2), tokenizer.eat(GREATERTHANSIGN$2), maybeMultiplied(tokenizer, {
        type: "Property",
        name
      });
    }(tokenizer) : readType(tokenizer);

   case VERTICALLINE$3:
    return {
      type: "Combinator",
      value: tokenizer.substringToPos(tokenizer.pos + (tokenizer.nextCharCode() === VERTICALLINE$3 ? 2 : 1))
    };

   case AMPERSAND$1:
    return tokenizer.pos++, tokenizer.eat(AMPERSAND$1), {
      type: "Combinator",
      value: "&&"
    };

   case COMMA:
    return tokenizer.pos++, {
      type: "Comma"
    };

   case APOSTROPHE$2:
    return maybeMultiplied(tokenizer, {
      type: "String",
      value: scanString(tokenizer)
    });

   case SPACE$2:
   case TAB:
   case N$1:
   case R:
   case F:
    return {
      type: "Spaces",
      value: scanSpaces(tokenizer)
    };

   case COMMERCIALAT:
    return code = tokenizer.nextCharCode(), code < 128 && 1 === NAME_CHAR[code] ? (tokenizer.pos++, 
    {
      type: "AtKeyword",
      name: scanWord(tokenizer)
    }) : maybeToken(tokenizer);

   case ASTERISK$6:
   case PLUSSIGN$6:
   case QUESTIONMARK$1:
   case NUMBERSIGN$3:
   case EXCLAMATIONMARK$2:
    break;

   case LEFTCURLYBRACKET:
    if (code = tokenizer.nextCharCode(), code < 48 || code > 57) return maybeToken(tokenizer);
    break;

   default:
    return maybeToken(tokenizer);
  }
}

parse$L.parse = function(source) {
  const tokenizer$1 = new tokenizer.Tokenizer(source), result = readImplicitGroup(tokenizer$1);
  return tokenizer$1.pos !== source.length && tokenizer$1.error("Unexpected input"), 
  1 === result.terms.length && "Group" === result.terms[0].type ? result.terms[0] : result;
};

const parse$J = parse$L, MATCH = {
  type: "Match"
}, MISMATCH = {
  type: "Mismatch"
}, DISALLOW_EMPTY = {
  type: "DisallowEmpty"
}, LEFTPARENTHESIS$1 = 40, RIGHTPARENTHESIS$1 = 41;

function createCondition(match, thenBranch, elseBranch) {
  return thenBranch === MATCH && elseBranch === MISMATCH || match === MATCH && thenBranch === MATCH && elseBranch === MATCH ? match : ("If" === match.type && match.else === MISMATCH && thenBranch === MATCH && (thenBranch = match.then, 
  match = match.match), {
    type: "If",
    match,
    then: thenBranch,
    else: elseBranch
  });
}

function isFunctionType(name) {
  return name.length > 2 && name.charCodeAt(name.length - 2) === LEFTPARENTHESIS$1 && name.charCodeAt(name.length - 1) === RIGHTPARENTHESIS$1;
}

function isEnumCapatible(term) {
  return "Keyword" === term.type || "AtKeyword" === term.type || "Function" === term.type || "Type" === term.type && isFunctionType(term.name);
}

function buildGroupMatchGraph(combinator, terms, atLeastOneTermMatched) {
  switch (combinator) {
   case " ":
    {
      let result = MATCH;
      for (let i = terms.length - 1; i >= 0; i--) {
        result = createCondition(terms[i], result, MISMATCH);
      }
      return result;
    }

   case "|":
    {
      let result = MISMATCH, map = null;
      for (let i = terms.length - 1; i >= 0; i--) {
        let term = terms[i];
        if (isEnumCapatible(term) && (null === map && i > 0 && isEnumCapatible(terms[i - 1]) && (map = Object.create(null), 
        result = createCondition({
          type: "Enum",
          map
        }, MATCH, result)), null !== map)) {
          const key = (isFunctionType(term.name) ? term.name.slice(0, -1) : term.name).toLowerCase();
          if (key in map == !1) {
            map[key] = term;
            continue;
          }
        }
        map = null, result = createCondition(term, MATCH, result);
      }
      return result;
    }

   case "&&":
    {
      if (terms.length > 5) return {
        type: "MatchOnce",
        terms,
        all: !0
      };
      let result = MISMATCH;
      for (let i = terms.length - 1; i >= 0; i--) {
        const term = terms[i];
        let thenClause;
        thenClause = terms.length > 1 ? buildGroupMatchGraph(combinator, terms.filter((function(newGroupTerm) {
          return newGroupTerm !== term;
        })), !1) : MATCH, result = createCondition(term, thenClause, result);
      }
      return result;
    }

   case "||":
    {
      if (terms.length > 5) return {
        type: "MatchOnce",
        terms,
        all: !1
      };
      let result = atLeastOneTermMatched ? MATCH : MISMATCH;
      for (let i = terms.length - 1; i >= 0; i--) {
        const term = terms[i];
        let thenClause;
        thenClause = terms.length > 1 ? buildGroupMatchGraph(combinator, terms.filter((function(newGroupTerm) {
          return newGroupTerm !== term;
        })), !0) : MATCH, result = createCondition(term, thenClause, result);
      }
      return result;
    }
  }
}

function buildMatchGraphInternal(node) {
  if ("function" == typeof node) return {
    type: "Generic",
    fn: node
  };
  switch (node.type) {
   case "Group":
    {
      let result = buildGroupMatchGraph(node.combinator, node.terms.map(buildMatchGraphInternal), !1);
      return node.disallowEmpty && (result = createCondition(result, DISALLOW_EMPTY, MISMATCH)), 
      result;
    }

   case "Multiplier":
    return function(node) {
      let result = MATCH, matchTerm = buildMatchGraphInternal(node.term);
      if (0 === node.max) matchTerm = createCondition(matchTerm, DISALLOW_EMPTY, MISMATCH), 
      result = createCondition(matchTerm, null, MISMATCH), result.then = createCondition(MATCH, MATCH, result), 
      node.comma && (result.then.else = createCondition({
        type: "Comma",
        syntax: node
      }, result, MISMATCH)); else for (let i = node.min || 1; i <= node.max; i++) node.comma && result !== MATCH && (result = createCondition({
        type: "Comma",
        syntax: node
      }, result, MISMATCH)), result = createCondition(matchTerm, createCondition(MATCH, MATCH, result), MISMATCH);
      if (0 === node.min) result = createCondition(MATCH, MATCH, result); else for (let i = 0; i < node.min - 1; i++) node.comma && result !== MATCH && (result = createCondition({
        type: "Comma",
        syntax: node
      }, result, MISMATCH)), result = createCondition(matchTerm, result, MISMATCH);
      return result;
    }(node);

   case "Type":
   case "Property":
    return {
      type: node.type,
      name: node.name,
      syntax: node
    };

   case "Keyword":
    return {
      type: node.type,
      name: node.name.toLowerCase(),
      syntax: node
    };

   case "AtKeyword":
    return {
      type: node.type,
      name: "@" + node.name.toLowerCase(),
      syntax: node
    };

   case "Function":
    return {
      type: node.type,
      name: node.name.toLowerCase() + "(",
      syntax: node
    };

   case "String":
    return 3 === node.value.length ? {
      type: "Token",
      value: node.value.charAt(1),
      syntax: node
    } : {
      type: node.type,
      value: node.value.substr(1, node.value.length - 2).replace(/\\'/g, "'"),
      syntax: node
    };

   case "Token":
    return {
      type: node.type,
      value: node.value,
      syntax: node
    };

   case "Comma":
    return {
      type: node.type,
      syntax: node
    };

   default:
    throw new Error("Unknown node type:", node.type);
  }
}

matchGraph$2.DISALLOW_EMPTY = DISALLOW_EMPTY, matchGraph$2.MATCH = MATCH, matchGraph$2.MISMATCH = MISMATCH, 
matchGraph$2.buildMatchGraph = function(syntaxTree, ref) {
  return "string" == typeof syntaxTree && (syntaxTree = parse$J.parse(syntaxTree)), 
  {
    type: "MatchGraph",
    match: buildMatchGraphInternal(syntaxTree),
    syntax: ref || null,
    source: syntaxTree
  };
};

var match$1 = {};

const matchGraph$1 = matchGraph$2, types$H = types$R, {hasOwnProperty: hasOwnProperty$9} = Object.prototype, STUB = 0, TOKEN = 1, OPEN_SYNTAX = 2, CLOSE_SYNTAX = 3, EXIT_REASON_MATCH = "Match", EXIT_REASON_MISMATCH = "Mismatch", EXIT_REASON_ITERATION_LIMIT = "Maximum iteration number exceeded (please fill an issue on https://github.com/csstree/csstree/issues)", ITERATION_LIMIT = 15000;

function reverseList(list) {
  let prev = null, next = null, item = list;
  for (;null !== item; ) next = item.prev, item.prev = prev, prev = item, item = next;
  return prev;
}

function areStringsEqualCaseInsensitive(testStr, referenceStr) {
  if (testStr.length !== referenceStr.length) return !1;
  for (let i = 0; i < testStr.length; i++) {
    const referenceCode = referenceStr.charCodeAt(i);
    let testCode = testStr.charCodeAt(i);
    if (testCode >= 0x0041 && testCode <= 0x005A && (testCode |= 32), testCode !== referenceCode) return !1;
  }
  return !0;
}

function isCommaContextStart(token) {
  return null === token || (token.type === types$H.Comma || token.type === types$H.Function || token.type === types$H.LeftParenthesis || token.type === types$H.LeftSquareBracket || token.type === types$H.LeftCurlyBracket || function(token) {
    return token.type === types$H.Delim && "?" !== token.value;
  }(token));
}

function isCommaContextEnd(token) {
  return null === token || (token.type === types$H.RightParenthesis || token.type === types$H.RightSquareBracket || token.type === types$H.RightCurlyBracket || token.type === types$H.Delim && "/" === token.value);
}

function internalMatch(tokens, state, syntaxes) {
  function moveToNextToken() {
    do {
      tokenIndex++, token = tokenIndex < tokens.length ? tokens[tokenIndex] : null;
    } while (null !== token && (token.type === types$H.WhiteSpace || token.type === types$H.Comment));
  }
  function getNextToken(offset) {
    const nextIndex = tokenIndex + offset;
    return nextIndex < tokens.length ? tokens[nextIndex] : null;
  }
  function stateSnapshotFromSyntax(nextState, prev) {
    return {
      nextState,
      matchStack,
      syntaxStack,
      thenStack,
      tokenIndex,
      prev
    };
  }
  function pushThenStack(nextState) {
    thenStack = {
      nextState,
      matchStack,
      syntaxStack,
      prev: thenStack
    };
  }
  function pushElseStack(nextState) {
    elseStack = stateSnapshotFromSyntax(nextState, elseStack);
  }
  function addTokenToMatch() {
    matchStack = {
      type: TOKEN,
      syntax: state.syntax,
      token,
      prev: matchStack
    }, moveToNextToken(), syntaxStash = null, tokenIndex > longestMatch && (longestMatch = tokenIndex);
  }
  function closeSyntax() {
    matchStack = matchStack.type === OPEN_SYNTAX ? matchStack.prev : {
      type: CLOSE_SYNTAX,
      syntax: syntaxStack.syntax,
      token: matchStack.token,
      prev: matchStack
    }, syntaxStack = syntaxStack.prev;
  }
  let syntaxStack = null, thenStack = null, elseStack = null, syntaxStash = null, iterationCount = 0, exitReason = null, token = null, tokenIndex = -1, longestMatch = 0, matchStack = {
    type: STUB,
    syntax: null,
    token: null,
    prev: null
  };
  for (moveToNextToken(); null === exitReason && ++iterationCount < ITERATION_LIMIT; ) switch (state.type) {
   case "Match":
    if (null === thenStack) {
      if (null !== token && (tokenIndex !== tokens.length - 1 || "\\0" !== token.value && "\\9" !== token.value)) {
        state = matchGraph$1.MISMATCH;
        break;
      }
      exitReason = EXIT_REASON_MATCH;
      break;
    }
    if ((state = thenStack.nextState) === matchGraph$1.DISALLOW_EMPTY) {
      if (thenStack.matchStack === matchStack) {
        state = matchGraph$1.MISMATCH;
        break;
      }
      state = matchGraph$1.MATCH;
    }
    for (;thenStack.syntaxStack !== syntaxStack; ) closeSyntax();
    thenStack = thenStack.prev;
    break;

   case "Mismatch":
    if (null !== syntaxStash && !1 !== syntaxStash) (null === elseStack || tokenIndex > elseStack.tokenIndex) && (elseStack = syntaxStash, 
    syntaxStash = !1); else if (null === elseStack) {
      exitReason = EXIT_REASON_MISMATCH;
      break;
    }
    state = elseStack.nextState, thenStack = elseStack.thenStack, syntaxStack = elseStack.syntaxStack, 
    matchStack = elseStack.matchStack, tokenIndex = elseStack.tokenIndex, token = tokenIndex < tokens.length ? tokens[tokenIndex] : null, 
    elseStack = elseStack.prev;
    break;

   case "MatchGraph":
    state = state.match;
    break;

   case "If":
    state.else !== matchGraph$1.MISMATCH && pushElseStack(state.else), state.then !== matchGraph$1.MATCH && pushThenStack(state.then), 
    state = state.match;
    break;

   case "MatchOnce":
    state = {
      type: "MatchOnceBuffer",
      syntax: state,
      index: 0,
      mask: 0
    };
    break;

   case "MatchOnceBuffer":
    {
      const terms = state.syntax.terms;
      if (state.index === terms.length) {
        if (0 === state.mask || state.syntax.all) {
          state = matchGraph$1.MISMATCH;
          break;
        }
        state = matchGraph$1.MATCH;
        break;
      }
      if (state.mask === (1 << terms.length) - 1) {
        state = matchGraph$1.MATCH;
        break;
      }
      for (;state.index < terms.length; state.index++) {
        const matchFlag = 1 << state.index;
        if (0 == (state.mask & matchFlag)) {
          pushElseStack(state), pushThenStack({
            type: "AddMatchOnce",
            syntax: state.syntax,
            mask: state.mask | matchFlag
          }), state = terms[state.index++];
          break;
        }
      }
      break;
    }

   case "AddMatchOnce":
    state = {
      type: "MatchOnceBuffer",
      syntax: state.syntax,
      index: 0,
      mask: state.mask
    };
    break;

   case "Enum":
    if (null !== token) {
      let name = token.value.toLowerCase();
      if (-1 !== name.indexOf("\\") && (name = name.replace(/\\[09].*$/, "")), hasOwnProperty$9.call(state.map, name)) {
        state = state.map[name];
        break;
      }
    }
    state = matchGraph$1.MISMATCH;
    break;

   case "Generic":
    {
      const opts = null !== syntaxStack ? syntaxStack.opts : null, lastTokenIndex = tokenIndex + Math.floor(state.fn(token, getNextToken, opts));
      if (!isNaN(lastTokenIndex) && lastTokenIndex > tokenIndex) {
        for (;tokenIndex < lastTokenIndex; ) addTokenToMatch();
        state = matchGraph$1.MATCH;
      } else state = matchGraph$1.MISMATCH;
      break;
    }

   case "Type":
   case "Property":
    {
      const syntaxDict = "Type" === state.type ? "types" : "properties", dictSyntax = hasOwnProperty$9.call(syntaxes, syntaxDict) ? syntaxes[syntaxDict][state.name] : null;
      if (!dictSyntax || !dictSyntax.match) throw new Error("Bad syntax reference: " + ("Type" === state.type ? "<" + state.name + ">" : "<'" + state.name + "'>"));
      if (!1 !== syntaxStash && null !== token && "Type" === state.type) {
        if ("custom-ident" === state.name && token.type === types$H.Ident || "length" === state.name && "0" === token.value) {
          null === syntaxStash && (syntaxStash = stateSnapshotFromSyntax(state, elseStack)), 
          state = matchGraph$1.MISMATCH;
          break;
        }
      }
      syntaxStack = {
        syntax: state.syntax,
        opts: state.syntax.opts || null !== syntaxStack && syntaxStack.opts || null,
        prev: syntaxStack
      }, matchStack = {
        type: OPEN_SYNTAX,
        syntax: state.syntax,
        token: matchStack.token,
        prev: matchStack
      }, state = dictSyntax.match;
      break;
    }

   case "Keyword":
    {
      const name = state.name;
      if (null !== token) {
        let keywordName = token.value;
        if (-1 !== keywordName.indexOf("\\") && (keywordName = keywordName.replace(/\\[09].*$/, "")), 
        areStringsEqualCaseInsensitive(keywordName, name)) {
          addTokenToMatch(), state = matchGraph$1.MATCH;
          break;
        }
      }
      state = matchGraph$1.MISMATCH;
      break;
    }

   case "AtKeyword":
   case "Function":
    if (null !== token && areStringsEqualCaseInsensitive(token.value, state.name)) {
      addTokenToMatch(), state = matchGraph$1.MATCH;
      break;
    }
    state = matchGraph$1.MISMATCH;
    break;

   case "Token":
    if (null !== token && token.value === state.value) {
      addTokenToMatch(), state = matchGraph$1.MATCH;
      break;
    }
    state = matchGraph$1.MISMATCH;
    break;

   case "Comma":
    null !== token && token.type === types$H.Comma ? isCommaContextStart(matchStack.token) ? state = matchGraph$1.MISMATCH : (addTokenToMatch(), 
    state = isCommaContextEnd(token) ? matchGraph$1.MISMATCH : matchGraph$1.MATCH) : state = isCommaContextStart(matchStack.token) || isCommaContextEnd(token) ? matchGraph$1.MATCH : matchGraph$1.MISMATCH;
    break;

   case "String":
    let string = "", lastTokenIndex = tokenIndex;
    for (;lastTokenIndex < tokens.length && string.length < state.value.length; lastTokenIndex++) string += tokens[lastTokenIndex].value;
    if (areStringsEqualCaseInsensitive(string, state.value)) {
      for (;tokenIndex < lastTokenIndex; ) addTokenToMatch();
      state = matchGraph$1.MATCH;
    } else state = matchGraph$1.MISMATCH;
    break;

   default:
    throw new Error("Unknown node type: " + state.type);
  }
  switch (exitReason) {
   case null:
    console.warn("[csstree-match] BREAK after " + ITERATION_LIMIT + " iterations"), 
    exitReason = EXIT_REASON_ITERATION_LIMIT, matchStack = null;
    break;

   case EXIT_REASON_MATCH:
    for (;null !== syntaxStack; ) closeSyntax();
    break;

   default:
    matchStack = null;
  }
  return {
    tokens,
    reason: exitReason,
    iterations: iterationCount,
    match: matchStack,
    longestMatch
  };
}

match$1.matchAsList = function(tokens, matchGraph, syntaxes) {
  const matchResult = internalMatch(tokens, matchGraph, syntaxes || {});
  if (null !== matchResult.match) {
    let item = reverseList(matchResult.match).prev;
    for (matchResult.match = []; null !== item; ) {
      switch (item.type) {
       case OPEN_SYNTAX:
       case CLOSE_SYNTAX:
        matchResult.match.push({
          type: item.type,
          syntax: item.syntax
        });
        break;

       default:
        matchResult.match.push({
          token: item.token.value,
          node: item.token.node
        });
      }
      item = item.prev;
    }
  }
  return matchResult;
}, match$1.matchAsTree = function(tokens, matchGraph, syntaxes) {
  const matchResult = internalMatch(tokens, matchGraph, syntaxes || {});
  if (null === matchResult.match) return matchResult;
  let item = matchResult.match, host = matchResult.match = {
    syntax: matchGraph.syntax || null,
    match: []
  };
  const hostStack = [ host ];
  for (item = reverseList(item).prev; null !== item; ) {
    switch (item.type) {
     case OPEN_SYNTAX:
      host.match.push(host = {
        syntax: item.syntax,
        match: []
      }), hostStack.push(host);
      break;

     case CLOSE_SYNTAX:
      hostStack.pop(), host = hostStack[hostStack.length - 1];
      break;

     default:
      host.match.push({
        syntax: item.syntax || null,
        token: item.token.value,
        node: item.token.node
      });
    }
    item = item.prev;
  }
  return matchResult;
};

var trace$1 = {};

function getTrace(node) {
  function shouldPutToTrace(syntax) {
    return null !== syntax && ("Type" === syntax.type || "Property" === syntax.type || "Keyword" === syntax.type);
  }
  let result = null;
  return null !== this.matched && function hasMatch(matchNode) {
    if (Array.isArray(matchNode.match)) {
      for (let i = 0; i < matchNode.match.length; i++) if (hasMatch(matchNode.match[i])) return shouldPutToTrace(matchNode.syntax) && result.unshift(matchNode.syntax), 
      !0;
    } else if (matchNode.node === node) return result = shouldPutToTrace(matchNode.syntax) ? [ matchNode.syntax ] : [], 
    !0;
    return !1;
  }(this.matched), result;
}

function testNode(match, node, fn) {
  const trace = getTrace.call(match, node);
  return null !== trace && trace.some(fn);
}

trace$1.getTrace = getTrace, trace$1.isKeyword = function(node) {
  return testNode(this, node, (match => "Keyword" === match.type));
}, trace$1.isProperty = function(node, property) {
  return testNode(this, node, (match => "Property" === match.type && match.name === property));
}, trace$1.isType = function(node, type) {
  return testNode(this, node, (match => "Type" === match.type && match.name === type));
};

var search$1 = {};

const List$3 = List$7;

function getFirstMatchNode(matchNode) {
  return "node" in matchNode ? matchNode.node : getFirstMatchNode(matchNode.match[0]);
}

function getLastMatchNode(matchNode) {
  return "node" in matchNode ? matchNode.node : getLastMatchNode(matchNode.match[matchNode.match.length - 1]);
}

search$1.matchFragments = function(lexer, ast, match, type, name) {
  const fragments = [];
  return null !== match.matched && function findFragments(matchNode) {
    if (null !== matchNode.syntax && matchNode.syntax.type === type && matchNode.syntax.name === name) {
      const start = getFirstMatchNode(matchNode), end = getLastMatchNode(matchNode);
      lexer.syntax.walk(ast, (function(node, item, list) {
        if (node === start) {
          const nodes = new List$3.List;
          do {
            if (nodes.appendData(item.data), item.data === end) break;
            item = item.next;
          } while (null !== item);
          fragments.push({
            parent: list,
            nodes
          });
        }
      }));
    }
    Array.isArray(matchNode.match) && matchNode.match.forEach(findFragments);
  }(match.matched), fragments;
};

var structure$F = {};

const List$2 = List$7, {hasOwnProperty: hasOwnProperty$8} = Object.prototype;

function isValidNumber(value) {
  return "number" == typeof value && isFinite(value) && Math.floor(value) === value && value >= 0;
}

function isValidLocation(loc) {
  return Boolean(loc) && isValidNumber(loc.offset) && isValidNumber(loc.line) && isValidNumber(loc.column);
}

function createNodeStructureChecker(type, fields) {
  return function(node, warn) {
    if (!node || node.constructor !== Object) return warn(node, "Type of node should be an Object");
    for (let key in node) {
      let valid = !0;
      if (!1 !== hasOwnProperty$8.call(node, key)) {
        if ("type" === key) node.type !== type && warn(node, "Wrong node type `" + node.type + "`, expected `" + type + "`"); else if ("loc" === key) {
          if (null === node.loc) continue;
          if (node.loc && node.loc.constructor === Object) if ("string" != typeof node.loc.source) key += ".source"; else if (isValidLocation(node.loc.start)) {
            if (isValidLocation(node.loc.end)) continue;
            key += ".end";
          } else key += ".start";
          valid = !1;
        } else if (fields.hasOwnProperty(key)) {
          valid = !1;
          for (let i = 0; !valid && i < fields[key].length; i++) {
            const fieldType = fields[key][i];
            switch (fieldType) {
             case String:
              valid = "string" == typeof node[key];
              break;

             case Boolean:
              valid = "boolean" == typeof node[key];
              break;

             case null:
              valid = null === node[key];
              break;

             default:
              "string" == typeof fieldType ? valid = node[key] && node[key].type === fieldType : Array.isArray(fieldType) && (valid = node[key] instanceof List$2.List);
            }
          }
        } else warn(node, "Unknown field `" + key + "` for " + type + " node type");
        valid || warn(node, "Bad value for `" + type + "." + key + "`");
      }
    }
    for (const key in fields) hasOwnProperty$8.call(fields, key) && !1 === hasOwnProperty$8.call(node, key) && warn(node, "Field `" + type + "." + key + "` is missed");
  };
}

function processStructure(name, nodeType) {
  const structure = nodeType.structure, fields = {
    type: String,
    loc: !0
  }, docs = {
    type: '"' + name + '"'
  };
  for (const key in structure) {
    if (!1 === hasOwnProperty$8.call(structure, key)) continue;
    const docsTypes = [], fieldTypes = fields[key] = Array.isArray(structure[key]) ? structure[key].slice() : [ structure[key] ];
    for (let i = 0; i < fieldTypes.length; i++) {
      const fieldType = fieldTypes[i];
      if (fieldType === String || fieldType === Boolean) docsTypes.push(fieldType.name); else if (null === fieldType) docsTypes.push("null"); else if ("string" == typeof fieldType) docsTypes.push("<" + fieldType + ">"); else {
        if (!Array.isArray(fieldType)) throw new Error("Wrong value `" + fieldType + "` in `" + name + "." + key + "` structure definition");
        docsTypes.push("List");
      }
    }
    docs[key] = docsTypes.join(" | ");
  }
  return {
    docs,
    check: createNodeStructureChecker(name, fields)
  };
}

structure$F.getStructureFromConfig = function(config) {
  const structure = {};
  if (config.node) for (const name in config.node) if (hasOwnProperty$8.call(config.node, name)) {
    const nodeType = config.node[name];
    if (!nodeType.structure) throw new Error("Missed `structure` field in `" + name + "` node type definition");
    structure[name] = processStructure(name, nodeType);
  }
  return structure;
};

var walk$5 = {};

const noop = function() {};

function ensureFunction(value) {
  return "function" == typeof value ? value : noop;
}

walk$5.walk = function(node, options, context) {
  let enter = noop, leave = noop;
  if ("function" == typeof options ? enter = options : options && (enter = ensureFunction(options.enter), 
  leave = ensureFunction(options.leave)), enter === noop && leave === noop) throw new Error("Neither `enter` nor `leave` walker handler is set or both aren't a function");
  !function walk(node) {
    switch (enter.call(context, node), node.type) {
     case "Group":
      node.terms.forEach(walk);
      break;

     case "Multiplier":
      walk(node.term);
      break;

     case "Type":
     case "Property":
     case "Keyword":
     case "AtKeyword":
     case "Function":
     case "String":
     case "Token":
     case "Comma":
      break;

     default:
      throw new Error("Unknown type: " + node.type);
    }
    leave.call(context, node);
  }(node);
};

const error$1 = error$2, names$3 = names$4, genericConst = genericConst$2, generic = generic$1, prepareTokens = prepareTokens_1, matchGraph = matchGraph$2, match = match$1, trace = trace$1, search = search$1, structure$E = structure$F, parse$I = parse$L, generate$I = generate$L, walk$3 = walk$5, cssWideKeywordsSyntax = matchGraph.buildMatchGraph(genericConst.cssWideKeywords.join(" | "));

function dumpMapSyntax(map, compact, syntaxAsAst) {
  const result = {};
  for (const name in map) map[name].syntax && (result[name] = syntaxAsAst ? map[name].syntax : generate$I.generate(map[name].syntax, {
    compact
  }));
  return result;
}

function dumpAtruleMapSyntax(map, compact, syntaxAsAst) {
  const result = {};
  for (const [name, atrule] of Object.entries(map)) result[name] = {
    prelude: atrule.prelude && (syntaxAsAst ? atrule.prelude.syntax : generate$I.generate(atrule.prelude.syntax, {
      compact
    })),
    descriptors: atrule.descriptors && dumpMapSyntax(atrule.descriptors, compact, syntaxAsAst)
  };
  return result;
}

function buildMatchResult(matched, error, iterations) {
  return {
    matched,
    iterations,
    error,
    ...trace
  };
}

function matchSyntax(lexer, syntax, value, useCssWideKeywords) {
  const tokens = prepareTokens(value, lexer.syntax);
  let result;
  return function(tokens) {
    for (let i = 0; i < tokens.length; i++) if ("var(" === tokens[i].value.toLowerCase()) return !0;
    return !1;
  }(tokens) ? buildMatchResult(null, new Error("Matching for a tree with var() is not supported")) : (useCssWideKeywords && (result = match.matchAsTree(tokens, lexer.cssWideKeywordsSyntax, lexer)), 
  useCssWideKeywords && result.match || (result = match.matchAsTree(tokens, syntax.match, lexer), 
  result.match) ? buildMatchResult(result.match, null, result.iterations) : buildMatchResult(null, new error$1.SyntaxMatchError(result.reason, syntax.syntax, value, result), result.iterations));
}

Lexer$3.Lexer = class {
  constructor(config, syntax, structure$1) {
    if (this.cssWideKeywordsSyntax = cssWideKeywordsSyntax, this.syntax = syntax, this.generic = !1, 
    this.atrules = Object.create(null), this.properties = Object.create(null), this.types = Object.create(null), 
    this.structure = structure$1 || structure$E.getStructureFromConfig(config), config) {
      if (config.types) for (const name in config.types) this.addType_(name, config.types[name]);
      if (config.generic) {
        this.generic = !0;
        for (const name in generic) this.addType_(name, generic[name]);
      }
      if (config.atrules) for (const name in config.atrules) this.addAtrule_(name, config.atrules[name]);
      if (config.properties) for (const name in config.properties) this.addProperty_(name, config.properties[name]);
    }
  }
  checkStructure(ast) {
    function collectWarning(node, message) {
      warns.push({
        node,
        message
      });
    }
    const structure = this.structure, warns = [];
    return this.syntax.walk(ast, (function(node) {
      structure.hasOwnProperty(node.type) ? structure[node.type].check(node, collectWarning) : collectWarning(node, "Unknown node type `" + node.type + "`");
    })), !!warns.length && warns;
  }
  createDescriptor(syntax, type, name, parent = null) {
    const ref = {
      type,
      name
    }, descriptor = {
      type,
      name,
      parent,
      serializable: "string" == typeof syntax || syntax && "string" == typeof syntax.type,
      syntax: null,
      match: null
    };
    return "function" == typeof syntax ? descriptor.match = matchGraph.buildMatchGraph(syntax, ref) : ("string" == typeof syntax ? Object.defineProperty(descriptor, "syntax", {
      get: () => (Object.defineProperty(descriptor, "syntax", {
        value: parse$I.parse(syntax)
      }), descriptor.syntax)
    }) : descriptor.syntax = syntax, Object.defineProperty(descriptor, "match", {
      get: () => (Object.defineProperty(descriptor, "match", {
        value: matchGraph.buildMatchGraph(descriptor.syntax, ref)
      }), descriptor.match)
    })), descriptor;
  }
  addAtrule_(name, syntax) {
    syntax && (this.atrules[name] = {
      type: "Atrule",
      name,
      prelude: syntax.prelude ? this.createDescriptor(syntax.prelude, "AtrulePrelude", name) : null,
      descriptors: syntax.descriptors ? Object.keys(syntax.descriptors).reduce(((map, descName) => (map[descName] = this.createDescriptor(syntax.descriptors[descName], "AtruleDescriptor", descName, name), 
      map)), Object.create(null)) : null
    });
  }
  addProperty_(name, syntax) {
    syntax && (this.properties[name] = this.createDescriptor(syntax, "Property", name));
  }
  addType_(name, syntax) {
    syntax && (this.types[name] = this.createDescriptor(syntax, "Type", name));
  }
  checkAtruleName(atruleName) {
    if (!this.getAtrule(atruleName)) return new error$1.SyntaxReferenceError("Unknown at-rule", "@" + atruleName);
  }
  checkAtrulePrelude(atruleName, prelude) {
    const error = this.checkAtruleName(atruleName);
    if (error) return error;
    const atrule = this.getAtrule(atruleName);
    return !atrule.prelude && prelude ? new SyntaxError("At-rule `@" + atruleName + "` should not contain a prelude") : !atrule.prelude || prelude || matchSyntax(this, atrule.prelude, "", !1).matched ? void 0 : new SyntaxError("At-rule `@" + atruleName + "` should contain a prelude");
  }
  checkAtruleDescriptorName(atruleName, descriptorName) {
    const error$1$1 = this.checkAtruleName(atruleName);
    if (error$1$1) return error$1$1;
    const atrule = this.getAtrule(atruleName), descriptor = names$3.keyword(descriptorName);
    return atrule.descriptors ? atrule.descriptors[descriptor.name] || atrule.descriptors[descriptor.basename] ? void 0 : new error$1.SyntaxReferenceError("Unknown at-rule descriptor", descriptorName) : new SyntaxError("At-rule `@" + atruleName + "` has no known descriptors");
  }
  checkPropertyName(propertyName) {
    if (!this.getProperty(propertyName)) return new error$1.SyntaxReferenceError("Unknown property", propertyName);
  }
  matchAtrulePrelude(atruleName, prelude) {
    const error = this.checkAtrulePrelude(atruleName, prelude);
    if (error) return buildMatchResult(null, error);
    const atrule = this.getAtrule(atruleName);
    return atrule.prelude ? matchSyntax(this, atrule.prelude, prelude || "", !1) : buildMatchResult(null, null);
  }
  matchAtruleDescriptor(atruleName, descriptorName, value) {
    const error = this.checkAtruleDescriptorName(atruleName, descriptorName);
    if (error) return buildMatchResult(null, error);
    const atrule = this.getAtrule(atruleName), descriptor = names$3.keyword(descriptorName);
    return matchSyntax(this, atrule.descriptors[descriptor.name] || atrule.descriptors[descriptor.basename], value, !1);
  }
  matchDeclaration(node) {
    return "Declaration" !== node.type ? buildMatchResult(null, new Error("Not a Declaration node")) : this.matchProperty(node.property, node.value);
  }
  matchProperty(propertyName, value) {
    if (names$3.property(propertyName).custom) return buildMatchResult(null, new Error("Lexer matching doesn't applicable for custom properties"));
    const error = this.checkPropertyName(propertyName);
    return error ? buildMatchResult(null, error) : matchSyntax(this, this.getProperty(propertyName), value, !0);
  }
  matchType(typeName, value) {
    const typeSyntax = this.getType(typeName);
    return typeSyntax ? matchSyntax(this, typeSyntax, value, !1) : buildMatchResult(null, new error$1.SyntaxReferenceError("Unknown type", typeName));
  }
  match(syntax, value) {
    return "string" == typeof syntax || syntax && syntax.type ? ("string" != typeof syntax && syntax.match || (syntax = this.createDescriptor(syntax, "Type", "anonymous")), 
    matchSyntax(this, syntax, value, !1)) : buildMatchResult(null, new error$1.SyntaxReferenceError("Bad syntax"));
  }
  findValueFragments(propertyName, value, type, name) {
    return search.matchFragments(this, value, this.matchProperty(propertyName, value), type, name);
  }
  findDeclarationValueFragments(declaration, type, name) {
    return search.matchFragments(this, declaration.value, this.matchDeclaration(declaration), type, name);
  }
  findAllFragments(ast, type, name) {
    const result = [];
    return this.syntax.walk(ast, {
      visit: "Declaration",
      enter: declaration => {
        result.push.apply(result, this.findDeclarationValueFragments(declaration, type, name));
      }
    }), result;
  }
  getAtrule(atruleName, fallbackBasename = !0) {
    const atrule = names$3.keyword(atruleName);
    return (atrule.vendor && fallbackBasename ? this.atrules[atrule.name] || this.atrules[atrule.basename] : this.atrules[atrule.name]) || null;
  }
  getAtrulePrelude(atruleName, fallbackBasename = !0) {
    const atrule = this.getAtrule(atruleName, fallbackBasename);
    return atrule && atrule.prelude || null;
  }
  getAtruleDescriptor(atruleName, name) {
    return this.atrules.hasOwnProperty(atruleName) && this.atrules.declarators && this.atrules[atruleName].declarators[name] || null;
  }
  getProperty(propertyName, fallbackBasename = !0) {
    const property = names$3.property(propertyName);
    return (property.vendor && fallbackBasename ? this.properties[property.name] || this.properties[property.basename] : this.properties[property.name]) || null;
  }
  getType(name) {
    return hasOwnProperty.call(this.types, name) ? this.types[name] : null;
  }
  validate() {
    function validate(syntax, name, broken, descriptor) {
      if (broken.has(name)) return broken.get(name);
      broken.set(name, !1), null !== descriptor.syntax && walk$3.walk(descriptor.syntax, (function(node) {
        if ("Type" !== node.type && "Property" !== node.type) return;
        const map = "Type" === node.type ? syntax.types : syntax.properties, brokenMap = "Type" === node.type ? brokenTypes : brokenProperties;
        hasOwnProperty.call(map, node.name) && !validate(syntax, node.name, brokenMap, map[node.name]) || broken.set(name, !0);
      }), this);
    }
    let brokenTypes = new Map, brokenProperties = new Map;
    for (const key in this.types) validate(this, key, brokenTypes, this.types[key]);
    for (const key in this.properties) validate(this, key, brokenProperties, this.properties[key]);
    return brokenTypes = [ ...brokenTypes.keys() ].filter((name => brokenTypes.get(name))), 
    brokenProperties = [ ...brokenProperties.keys() ].filter((name => brokenProperties.get(name))), 
    brokenTypes.length || brokenProperties.length ? {
      types: brokenTypes,
      properties: brokenProperties
    } : null;
  }
  dump(syntaxAsAst, pretty) {
    return {
      generic: this.generic,
      types: dumpMapSyntax(this.types, !pretty, syntaxAsAst),
      properties: dumpMapSyntax(this.properties, !pretty, syntaxAsAst),
      atrules: dumpAtruleMapSyntax(this.atrules, !pretty, syntaxAsAst)
    };
  }
  toString() {
    return JSON.stringify(this.dump());
  }
};

const {hasOwnProperty: hasOwnProperty$7} = Object.prototype, shape = {
  generic: !0,
  types: appendOrAssign,
  atrules: {
    prelude: appendOrAssignOrNull,
    descriptors: appendOrAssignOrNull
  },
  properties: appendOrAssign,
  parseContext: function(dest, src) {
    return Object.assign(dest, src);
  },
  scope: function deepAssign(dest, src) {
    for (const key in src) hasOwnProperty$7.call(src, key) && (isObject(dest[key]) ? deepAssign(dest[key], src[key]) : dest[key] = copy(src[key]));
    return dest;
  },
  atrule: [ "parse" ],
  pseudo: [ "parse" ],
  node: [ "name", "structure", "parse", "generate", "walkContext" ]
};

function isObject(value) {
  return value && value.constructor === Object;
}

function copy(value) {
  return isObject(value) ? {
    ...value
  } : value;
}

function append(a, b) {
  return "string" == typeof b && /^\s*\|/.test(b) ? "string" == typeof a ? a + b : b.replace(/^\s*\|\s*/, "") : b || null;
}

function appendOrAssign(a, b) {
  if ("string" == typeof b) return append(a, b);
  const result = {
    ...a
  };
  for (let key in b) hasOwnProperty$7.call(b, key) && (result[key] = append(hasOwnProperty$7.call(a, key) ? a[key] : void 0, b[key]));
  return result;
}

function appendOrAssignOrNull(a, b) {
  const result = appendOrAssign(a, b);
  return !isObject(result) || Object.keys(result).length ? result : null;
}

function mix$1(dest, src, shape) {
  for (const key in shape) if (!1 !== hasOwnProperty$7.call(shape, key)) if (!0 === shape[key]) hasOwnProperty$7.call(src, key) && (dest[key] = copy(src[key])); else if (shape[key]) if ("function" == typeof shape[key]) {
    const fn = shape[key];
    dest[key] = fn({}, dest[key]), dest[key] = fn(dest[key] || {}, src[key]);
  } else if (isObject(shape[key])) {
    const result = {};
    for (let name in dest[key]) result[name] = mix$1({}, dest[key][name], shape[key]);
    for (let name in src[key]) result[name] = mix$1(result[name] || {}, src[key][name], shape[key]);
    dest[key] = result;
  } else if (Array.isArray(shape[key])) {
    const res = {}, innerShape = shape[key].reduce((function(s, k) {
      return s[k] = !0, s;
    }), {});
    for (const [name, value] of Object.entries(dest[key] || {})) res[name] = {}, value && mix$1(res[name], value, innerShape);
    for (const name in src[key]) hasOwnProperty$7.call(src[key], name) && (res[name] || (res[name] = {}), 
    src[key] && src[key][name] && mix$1(res[name], src[key][name], innerShape));
    dest[key] = res;
  }
  return dest;
}

const index$8 = tokenizer$2, create$2 = create$7, create$2$1 = create$6, create$3 = create$5, create$1$1 = create$4, Lexer$1 = Lexer$3, mix = (dest, src) => mix$1(dest, src, shape);

function createSyntax(config) {
  const parse = create$2.createParser(config), walk = create$1$1.createWalker(config), generate = create$2$1.createGenerator(config), {fromPlainObject, toPlainObject} = create$3.createConvertor(walk), syntax = {
    lexer: null,
    createLexer: config => new Lexer$1.Lexer(config, syntax, syntax.lexer.structure),
    tokenize: index$8.tokenize,
    parse,
    generate,
    walk,
    find: walk.find,
    findLast: walk.findLast,
    findAll: walk.findAll,
    fromPlainObject,
    toPlainObject,
    fork(extension) {
      const base = mix({}, config);
      return createSyntax("function" == typeof extension ? extension(base, Object.assign) : mix(base, extension));
    }
  };
  return syntax.lexer = new Lexer$1.Lexer({
    generic: !0,
    types: config.types,
    atrules: config.atrules,
    properties: config.properties,
    node: config.node
  }, syntax), syntax;
}

var create_1 = config => createSyntax(mix({}, config)), node = {}, AnPlusB$2 = {};

const types$G = types$R, charCodeDefinitions$5 = charCodeDefinitions$c, PLUSSIGN$5 = 0x002B, HYPHENMINUS$2 = 0x002D, N = 0x006E, DISALLOW_SIGN = !0;

function checkInteger(offset, disallowSign) {
  let pos = this.tokenStart + offset;
  const code = this.charCodeAt(pos);
  for (code !== PLUSSIGN$5 && code !== HYPHENMINUS$2 || (disallowSign && this.error("Number sign is not allowed"), 
  pos++); pos < this.tokenEnd; pos++) charCodeDefinitions$5.isDigit(this.charCodeAt(pos)) || this.error("Integer is expected", pos);
}

function checkTokenIsInteger(disallowSign) {
  return checkInteger.call(this, 0, disallowSign);
}

function expectCharCode(offset, code) {
  if (!this.cmpChar(this.tokenStart + offset, code)) {
    let msg = "";
    switch (code) {
     case N:
      msg = "N is expected";
      break;

     case HYPHENMINUS$2:
      msg = "HyphenMinus is expected";
    }
    this.error(msg, this.tokenStart + offset);
  }
}

function consumeB() {
  let offset = 0, sign = 0, type = this.tokenType;
  for (;type === types$G.WhiteSpace || type === types$G.Comment; ) type = this.lookupType(++offset);
  if (type !== types$G.Number) {
    if (!this.isDelim(PLUSSIGN$5, offset) && !this.isDelim(HYPHENMINUS$2, offset)) return null;
    sign = this.isDelim(PLUSSIGN$5, offset) ? PLUSSIGN$5 : HYPHENMINUS$2;
    do {
      type = this.lookupType(++offset);
    } while (type === types$G.WhiteSpace || type === types$G.Comment);
    type !== types$G.Number && (this.skip(offset), checkTokenIsInteger.call(this, DISALLOW_SIGN));
  }
  return offset > 0 && this.skip(offset), 0 === sign && (type = this.charCodeAt(this.tokenStart), 
  type !== PLUSSIGN$5 && type !== HYPHENMINUS$2 && this.error("Number sign is expected")), 
  checkTokenIsInteger.call(this, 0 !== sign), sign === HYPHENMINUS$2 ? "-" + this.consume(types$G.Number) : this.consume(types$G.Number);
}

const structure$D = {
  a: [ String, null ],
  b: [ String, null ]
};

AnPlusB$2.generate = function(node) {
  if (node.a) {
    const a = ("+1" === node.a || "1" === node.a ? "n" : "-1" === node.a && "-n") || node.a + "n";
    if (node.b) {
      const b = "-" === node.b[0] || "+" === node.b[0] ? node.b : "+" + node.b;
      this.tokenize(a + b);
    } else this.tokenize(a);
  } else this.tokenize(node.b);
}, AnPlusB$2.name = "AnPlusB", AnPlusB$2.parse = function() {
  const start = this.tokenStart;
  let a = null, b = null;
  if (this.tokenType === types$G.Number) checkTokenIsInteger.call(this, false), b = this.consume(types$G.Number); else if (this.tokenType === types$G.Ident && this.cmpChar(this.tokenStart, HYPHENMINUS$2)) switch (a = "-1", 
  expectCharCode.call(this, 1, N), this.tokenEnd - this.tokenStart) {
   case 2:
    this.next(), b = consumeB.call(this);
    break;

   case 3:
    expectCharCode.call(this, 2, HYPHENMINUS$2), this.next(), this.skipSC(), checkTokenIsInteger.call(this, DISALLOW_SIGN), 
    b = "-" + this.consume(types$G.Number);
    break;

   default:
    expectCharCode.call(this, 2, HYPHENMINUS$2), checkInteger.call(this, 3, DISALLOW_SIGN), 
    this.next(), b = this.substrToCursor(start + 2);
  } else if (this.tokenType === types$G.Ident || this.isDelim(PLUSSIGN$5) && this.lookupType(1) === types$G.Ident) {
    let sign = 0;
    switch (a = "1", this.isDelim(PLUSSIGN$5) && (sign = 1, this.next()), expectCharCode.call(this, 0, N), 
    this.tokenEnd - this.tokenStart) {
     case 1:
      this.next(), b = consumeB.call(this);
      break;

     case 2:
      expectCharCode.call(this, 1, HYPHENMINUS$2), this.next(), this.skipSC(), checkTokenIsInteger.call(this, DISALLOW_SIGN), 
      b = "-" + this.consume(types$G.Number);
      break;

     default:
      expectCharCode.call(this, 1, HYPHENMINUS$2), checkInteger.call(this, 2, DISALLOW_SIGN), 
      this.next(), b = this.substrToCursor(start + sign + 1);
    }
  } else if (this.tokenType === types$G.Dimension) {
    const code = this.charCodeAt(this.tokenStart), sign = code === PLUSSIGN$5 || code === HYPHENMINUS$2;
    let i = this.tokenStart + sign;
    for (;i < this.tokenEnd && charCodeDefinitions$5.isDigit(this.charCodeAt(i)); i++) ;
    i === this.tokenStart + sign && this.error("Integer is expected", this.tokenStart + sign), 
    expectCharCode.call(this, i - this.tokenStart, N), a = this.substring(start, i), 
    i + 1 === this.tokenEnd ? (this.next(), b = consumeB.call(this)) : (expectCharCode.call(this, i - this.tokenStart + 1, HYPHENMINUS$2), 
    i + 2 === this.tokenEnd ? (this.next(), this.skipSC(), checkTokenIsInteger.call(this, DISALLOW_SIGN), 
    b = "-" + this.consume(types$G.Number)) : (checkInteger.call(this, i - this.tokenStart + 2, DISALLOW_SIGN), 
    this.next(), b = this.substrToCursor(i + 1)));
  } else this.error();
  return null !== a && a.charCodeAt(0) === PLUSSIGN$5 && (a = a.substr(1)), null !== b && b.charCodeAt(0) === PLUSSIGN$5 && (b = b.substr(1)), 
  {
    type: "AnPlusB",
    loc: this.getLocation(start, this.tokenStart),
    a,
    b
  };
}, AnPlusB$2.structure = structure$D;

var Atrule$6 = {};

const types$F = types$R;

function consumeRaw$5(startToken) {
  return this.Raw(startToken, this.consumeUntilLeftCurlyBracketOrSemicolon, !0);
}

function isDeclarationBlockAtrule() {
  for (let type, offset = 1; type = this.lookupType(offset); offset++) {
    if (type === types$F.RightCurlyBracket) return !0;
    if (type === types$F.LeftCurlyBracket || type === types$F.AtKeyword) return !1;
  }
  return !1;
}

const structure$C = {
  name: String,
  prelude: [ "AtrulePrelude", "Raw", null ],
  block: [ "Block", null ]
};

Atrule$6.generate = function(node) {
  this.token(types$F.AtKeyword, "@" + node.name), null !== node.prelude && this.node(node.prelude), 
  node.block ? this.node(node.block) : this.token(types$F.Semicolon, ";");
}, Atrule$6.name = "Atrule", Atrule$6.parse = function() {
  const start = this.tokenStart;
  let name, nameLowerCase, prelude = null, block = null;
  switch (this.eat(types$F.AtKeyword), name = this.substrToCursor(start + 1), nameLowerCase = name.toLowerCase(), 
  this.skipSC(), !1 === this.eof && this.tokenType !== types$F.LeftCurlyBracket && this.tokenType !== types$F.Semicolon && (prelude = this.parseAtrulePrelude ? this.parseWithFallback(this.AtrulePrelude.bind(this, name), consumeRaw$5) : consumeRaw$5.call(this, this.tokenIndex), 
  this.skipSC()), this.tokenType) {
   case types$F.Semicolon:
    this.next();
    break;

   case types$F.LeftCurlyBracket:
    block = hasOwnProperty.call(this.atrule, nameLowerCase) && "function" == typeof this.atrule[nameLowerCase].block ? this.atrule[nameLowerCase].block.call(this) : this.Block(isDeclarationBlockAtrule.call(this));
  }
  return {
    type: "Atrule",
    loc: this.getLocation(start, this.tokenStart),
    name,
    prelude,
    block
  };
}, Atrule$6.structure = structure$C, Atrule$6.walkContext = "atrule";

var AtrulePrelude$2 = {};

const types$E = types$R;

AtrulePrelude$2.generate = function(node) {
  this.children(node);
}, AtrulePrelude$2.name = "AtrulePrelude", AtrulePrelude$2.parse = function(name) {
  let children = null;
  return null !== name && (name = name.toLowerCase()), this.skipSC(), children = hasOwnProperty.call(this.atrule, name) && "function" == typeof this.atrule[name].prelude ? this.atrule[name].prelude.call(this) : this.readSequence(this.scope.AtrulePrelude), 
  this.skipSC(), !0 !== this.eof && this.tokenType !== types$E.LeftCurlyBracket && this.tokenType !== types$E.Semicolon && this.error("Semicolon or block is expected"), 
  {
    type: "AtrulePrelude",
    loc: this.getLocationFromList(children),
    children
  };
}, AtrulePrelude$2.structure = {
  children: [ [] ]
}, AtrulePrelude$2.walkContext = "atrulePrelude";

var AttributeSelector$4 = {};

const types$D = types$R, DOLLARSIGN$1 = 0x0024, ASTERISK$5 = 0x002A, EQUALSSIGN = 0x003D, CIRCUMFLEXACCENT = 0x005E, VERTICALLINE$2 = 0x007C, TILDE$2 = 0x007E;

function getAttributeName() {
  this.eof && this.error("Unexpected end of input");
  const start = this.tokenStart;
  let expectIdent = !1;
  return this.isDelim(ASTERISK$5) ? (expectIdent = !0, this.next()) : this.isDelim(VERTICALLINE$2) || this.eat(types$D.Ident), 
  this.isDelim(VERTICALLINE$2) ? this.charCodeAt(this.tokenStart + 1) !== EQUALSSIGN ? (this.next(), 
  this.eat(types$D.Ident)) : expectIdent && this.error("Identifier is expected", this.tokenEnd) : expectIdent && this.error("Vertical line is expected"), 
  {
    type: "Identifier",
    loc: this.getLocation(start, this.tokenStart),
    name: this.substrToCursor(start)
  };
}

function getOperator() {
  const start = this.tokenStart, code = this.charCodeAt(start);
  return code !== EQUALSSIGN && code !== TILDE$2 && code !== CIRCUMFLEXACCENT && code !== DOLLARSIGN$1 && code !== ASTERISK$5 && code !== VERTICALLINE$2 && this.error("Attribute selector (=, ~=, ^=, $=, *=, |=) is expected"), 
  this.next(), code !== EQUALSSIGN && (this.isDelim(EQUALSSIGN) || this.error("Equal sign is expected"), 
  this.next()), this.substrToCursor(start);
}

const structure$A = {
  name: "Identifier",
  matcher: [ String, null ],
  value: [ "String", "Identifier", null ],
  flags: [ String, null ]
};

AttributeSelector$4.generate = function(node) {
  this.token(types$D.Delim, "["), this.node(node.name), null !== node.matcher && (this.tokenize(node.matcher), 
  this.node(node.value)), null !== node.flags && this.token(types$D.Ident, node.flags), 
  this.token(types$D.Delim, "]");
}, AttributeSelector$4.name = "AttributeSelector", AttributeSelector$4.parse = function() {
  const start = this.tokenStart;
  let name, matcher = null, value = null, flags = null;
  return this.eat(types$D.LeftSquareBracket), this.skipSC(), name = getAttributeName.call(this), 
  this.skipSC(), this.tokenType !== types$D.RightSquareBracket && (this.tokenType !== types$D.Ident && (matcher = getOperator.call(this), 
  this.skipSC(), value = this.tokenType === types$D.String ? this.String() : this.Identifier(), 
  this.skipSC()), this.tokenType === types$D.Ident && (flags = this.consume(types$D.Ident), 
  this.skipSC())), this.eat(types$D.RightSquareBracket), {
    type: "AttributeSelector",
    loc: this.getLocation(start, this.tokenStart),
    name,
    matcher,
    value,
    flags
  };
}, AttributeSelector$4.structure = structure$A;

var Block$2 = {};

const types$C = types$R;

function consumeRaw$4(startToken) {
  return this.Raw(startToken, null, !0);
}

function consumeRule() {
  return this.parseWithFallback(this.Rule, consumeRaw$4);
}

function consumeRawDeclaration(startToken) {
  return this.Raw(startToken, this.consumeUntilSemicolonIncluded, !0);
}

function consumeDeclaration() {
  if (this.tokenType === types$C.Semicolon) return consumeRawDeclaration.call(this, this.tokenIndex);
  const node = this.parseWithFallback(this.Declaration, consumeRawDeclaration);
  return this.tokenType === types$C.Semicolon && this.next(), node;
}

Block$2.generate = function(node) {
  this.token(types$C.LeftCurlyBracket, "{"), this.children(node, (prev => {
    "Declaration" === prev.type && this.token(types$C.Semicolon, ";");
  })), this.token(types$C.RightCurlyBracket, "}");
}, Block$2.name = "Block", Block$2.parse = function(isDeclaration) {
  const consumer = isDeclaration ? consumeDeclaration : consumeRule, start = this.tokenStart;
  let children = this.createList();
  this.eat(types$C.LeftCurlyBracket);
  scan: for (;!this.eof; ) switch (this.tokenType) {
   case types$C.RightCurlyBracket:
    break scan;

   case types$C.WhiteSpace:
   case types$C.Comment:
    this.next();
    break;

   case types$C.AtKeyword:
    children.push(this.parseWithFallback(this.Atrule, consumeRaw$4));
    break;

   default:
    children.push(consumer.call(this));
  }
  return this.eof || this.eat(types$C.RightCurlyBracket), {
    type: "Block",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, Block$2.structure = {
  children: [ [ "Atrule", "Rule", "Declaration" ] ]
}, Block$2.walkContext = "block";

var Brackets$2 = {};

const types$B = types$R;

Brackets$2.generate = function(node) {
  this.token(types$B.Delim, "["), this.children(node), this.token(types$B.Delim, "]");
}, Brackets$2.name = "Brackets", Brackets$2.parse = function(readSequence, recognizer) {
  const start = this.tokenStart;
  let children = null;
  return this.eat(types$B.LeftSquareBracket), children = readSequence.call(this, recognizer), 
  this.eof || this.eat(types$B.RightSquareBracket), {
    type: "Brackets",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, Brackets$2.structure = {
  children: [ [] ]
};

var CDC$2 = {};

const types$A = types$R;

CDC$2.generate = function() {
  this.token(types$A.CDC, "--\x3e");
}, CDC$2.name = "CDC", CDC$2.parse = function() {
  const start = this.tokenStart;
  return this.eat(types$A.CDC), {
    type: "CDC",
    loc: this.getLocation(start, this.tokenStart)
  };
}, CDC$2.structure = [];

var CDO$2 = {};

const types$z = types$R;

CDO$2.generate = function() {
  this.token(types$z.CDO, "\x3c!--");
}, CDO$2.name = "CDO", CDO$2.parse = function() {
  const start = this.tokenStart;
  return this.eat(types$z.CDO), {
    type: "CDO",
    loc: this.getLocation(start, this.tokenStart)
  };
}, CDO$2.structure = [];

var ClassSelector$2 = {};

const types$y = types$R, structure$v = {
  name: String
};

ClassSelector$2.generate = function(node) {
  this.token(types$y.Delim, "."), this.token(types$y.Ident, node.name);
}, ClassSelector$2.name = "ClassSelector", ClassSelector$2.parse = function() {
  return this.eatDelim(46), {
    type: "ClassSelector",
    loc: this.getLocation(this.tokenStart - 1, this.tokenEnd),
    name: this.consume(types$y.Ident)
  };
}, ClassSelector$2.structure = structure$v;

var Combinator$2 = {};

const types$x = types$R, structure$u = {
  name: String
};

Combinator$2.generate = function(node) {
  this.tokenize(node.name);
}, Combinator$2.name = "Combinator", Combinator$2.parse = function() {
  const start = this.tokenStart;
  let name;
  switch (this.tokenType) {
   case types$x.WhiteSpace:
    name = " ";
    break;

   case types$x.Delim:
    switch (this.charCodeAt(this.tokenStart)) {
     case 62:
     case 43:
     case 126:
      this.next();
      break;

     case 47:
      this.next(), this.eatIdent("deep"), this.eatDelim(47);
      break;

     default:
      this.error("Combinator is expected");
    }
    name = this.substrToCursor(start);
  }
  return {
    type: "Combinator",
    loc: this.getLocation(start, this.tokenStart),
    name
  };
}, Combinator$2.structure = structure$u;

var Comment$4 = {};

const types$w = types$R, structure$t = {
  value: String
};

Comment$4.generate = function(node) {
  this.token(types$w.Comment, "/*" + node.value + "*/");
}, Comment$4.name = "Comment", Comment$4.parse = function() {
  const start = this.tokenStart;
  let end = this.tokenEnd;
  return this.eat(types$w.Comment), end - start + 2 >= 2 && 42 === this.charCodeAt(end - 2) && 47 === this.charCodeAt(end - 1) && (end -= 2), 
  {
    type: "Comment",
    loc: this.getLocation(start, this.tokenStart),
    value: this.substring(start + 2, end)
  };
}, Comment$4.structure = structure$t;

var Declaration$4 = {};

const names$2 = names$4, types$v = types$R, EXCLAMATIONMARK$1 = 0x0021, NUMBERSIGN$2 = 0x0023, DOLLARSIGN = 0x0024, AMPERSAND = 0x0026, ASTERISK$3 = 0x002A, PLUSSIGN$3 = 0x002B, SOLIDUS$3 = 0x002F;

function consumeValueRaw(startToken) {
  return this.Raw(startToken, this.consumeUntilExclamationMarkOrSemicolon, !0);
}

function consumeCustomPropertyRaw(startToken) {
  return this.Raw(startToken, this.consumeUntilExclamationMarkOrSemicolon, !1);
}

function consumeValue() {
  const startValueToken = this.tokenIndex, value = this.Value();
  return "Raw" !== value.type && !1 === this.eof && this.tokenType !== types$v.Semicolon && !1 === this.isDelim(EXCLAMATIONMARK$1) && !1 === this.isBalanceEdge(startValueToken) && this.error(), 
  value;
}

const structure$s = {
  important: [ Boolean, String ],
  property: String,
  value: [ "Value", "Raw" ]
};

function readProperty() {
  const start = this.tokenStart;
  if (this.tokenType === types$v.Delim) switch (this.charCodeAt(this.tokenStart)) {
   case ASTERISK$3:
   case DOLLARSIGN:
   case PLUSSIGN$3:
   case NUMBERSIGN$2:
   case AMPERSAND:
    this.next();
    break;

   case SOLIDUS$3:
    this.next(), this.isDelim(SOLIDUS$3) && this.next();
  }
  return this.tokenType === types$v.Hash ? this.eat(types$v.Hash) : this.eat(types$v.Ident), 
  this.substrToCursor(start);
}

function getImportant() {
  this.eat(types$v.Delim), this.skipSC();
  const important = this.consume(types$v.Ident);
  return "important" === important || important;
}

Declaration$4.generate = function(node) {
  this.token(types$v.Ident, node.property), this.token(types$v.Colon, ":"), this.node(node.value), 
  node.important && (this.token(types$v.Delim, "!"), this.token(types$v.Ident, !0 === node.important ? "important" : node.important));
}, Declaration$4.name = "Declaration", Declaration$4.parse = function() {
  const start = this.tokenStart, startToken = this.tokenIndex, property = readProperty.call(this), customProperty = names$2.isCustomProperty(property), parseValue = customProperty ? this.parseCustomProperty : this.parseValue, consumeRaw = customProperty ? consumeCustomPropertyRaw : consumeValueRaw;
  let value, important = !1;
  this.skipSC(), this.eat(types$v.Colon);
  const valueStart = this.tokenIndex;
  if (customProperty || this.skipSC(), value = parseValue ? this.parseWithFallback(consumeValue, consumeRaw) : consumeRaw.call(this, this.tokenIndex), 
  customProperty && "Value" === value.type && value.children.isEmpty) for (let offset = valueStart - this.tokenIndex; offset <= 0; offset++) if (this.lookupType(offset) === types$v.WhiteSpace) {
    value.children.appendData({
      type: "WhiteSpace",
      loc: null,
      value: " "
    });
    break;
  }
  return this.isDelim(EXCLAMATIONMARK$1) && (important = getImportant.call(this), 
  this.skipSC()), !1 === this.eof && this.tokenType !== types$v.Semicolon && !1 === this.isBalanceEdge(startToken) && this.error(), 
  {
    type: "Declaration",
    loc: this.getLocation(start, this.tokenStart),
    important,
    property,
    value
  };
}, Declaration$4.structure = structure$s, Declaration$4.walkContext = "declaration";

var DeclarationList$2 = {};

const types$u = types$R;

function consumeRaw$3(startToken) {
  return this.Raw(startToken, this.consumeUntilSemicolonIncluded, !0);
}

DeclarationList$2.generate = function(node) {
  this.children(node, (prev => {
    "Declaration" === prev.type && this.token(types$u.Semicolon, ";");
  }));
}, DeclarationList$2.name = "DeclarationList", DeclarationList$2.parse = function() {
  const children = this.createList();
  for (;!this.eof; ) switch (this.tokenType) {
   case types$u.WhiteSpace:
   case types$u.Comment:
   case types$u.Semicolon:
    this.next();
    break;

   default:
    children.push(this.parseWithFallback(this.Declaration, consumeRaw$3));
  }
  return {
    type: "DeclarationList",
    loc: this.getLocationFromList(children),
    children
  };
}, DeclarationList$2.structure = {
  children: [ [ "Declaration" ] ]
};

var Dimension$4 = {};

const types$t = types$R, structure$q = {
  value: String,
  unit: String
};

Dimension$4.generate = function(node) {
  this.token(types$t.Dimension, node.value + node.unit);
}, Dimension$4.name = "Dimension", Dimension$4.parse = function() {
  const start = this.tokenStart, value = this.consumeNumber(types$t.Dimension);
  return {
    type: "Dimension",
    loc: this.getLocation(start, this.tokenStart),
    value,
    unit: this.substring(start + value.length, this.tokenStart)
  };
}, Dimension$4.structure = structure$q;

var _Function = {};

const types$s = types$R, structure$p = {
  name: String,
  children: [ [] ]
};

_Function.generate = function(node) {
  this.token(types$s.Function, node.name + "("), this.children(node), this.token(types$s.RightParenthesis, ")");
}, _Function.name = "Function", _Function.parse = function(readSequence, recognizer) {
  const start = this.tokenStart, name = this.consumeFunctionName(), nameLowerCase = name.toLowerCase();
  let children;
  return children = recognizer.hasOwnProperty(nameLowerCase) ? recognizer[nameLowerCase].call(this, recognizer) : readSequence.call(this, recognizer), 
  this.eof || this.eat(types$s.RightParenthesis), {
    type: "Function",
    loc: this.getLocation(start, this.tokenStart),
    name,
    children
  };
}, _Function.structure = structure$p, _Function.walkContext = "function";

var Hash$2 = {};

const types$r = types$R, structure$o = {
  value: String
};

Hash$2.generate = function(node) {
  this.token(types$r.Hash, "#" + node.value);
}, Hash$2.name = "Hash", Hash$2.parse = function() {
  const start = this.tokenStart;
  return this.eat(types$r.Hash), {
    type: "Hash",
    loc: this.getLocation(start, this.tokenStart),
    value: this.substrToCursor(start + 1)
  };
}, Hash$2.structure = structure$o, Hash$2.xxx = "XXX";

var Identifier$2 = {};

const types$q = types$R, structure$n = {
  name: String
};

Identifier$2.generate = function(node) {
  this.token(types$q.Ident, node.name);
}, Identifier$2.name = "Identifier", Identifier$2.parse = function() {
  return {
    type: "Identifier",
    loc: this.getLocation(this.tokenStart, this.tokenEnd),
    name: this.consume(types$q.Ident)
  };
}, Identifier$2.structure = structure$n;

var IdSelector$2 = {};

const types$p = types$R, structure$m = {
  name: String
};

IdSelector$2.generate = function(node) {
  this.token(types$p.Delim, "#" + node.name);
}, IdSelector$2.name = "IdSelector", IdSelector$2.parse = function() {
  const start = this.tokenStart;
  return this.eat(types$p.Hash), {
    type: "IdSelector",
    loc: this.getLocation(start, this.tokenStart),
    name: this.substrToCursor(start + 1)
  };
}, IdSelector$2.structure = structure$m;

var MediaFeature$2 = {};

const types$o = types$R, structure$l = {
  name: String,
  value: [ "Identifier", "Number", "Dimension", "Ratio", null ]
};

MediaFeature$2.generate = function(node) {
  this.token(types$o.LeftParenthesis, "("), this.token(types$o.Ident, node.name), 
  null !== node.value && (this.token(types$o.Colon, ":"), this.node(node.value)), 
  this.token(types$o.RightParenthesis, ")");
}, MediaFeature$2.name = "MediaFeature", MediaFeature$2.parse = function() {
  const start = this.tokenStart;
  let name, value = null;
  if (this.eat(types$o.LeftParenthesis), this.skipSC(), name = this.consume(types$o.Ident), 
  this.skipSC(), this.tokenType !== types$o.RightParenthesis) {
    switch (this.eat(types$o.Colon), this.skipSC(), this.tokenType) {
     case types$o.Number:
      value = this.lookupNonWSType(1) === types$o.Delim ? this.Ratio() : this.Number();
      break;

     case types$o.Dimension:
      value = this.Dimension();
      break;

     case types$o.Ident:
      value = this.Identifier();
      break;

     default:
      this.error("Number, dimension, ratio or identifier is expected");
    }
    this.skipSC();
  }
  return this.eat(types$o.RightParenthesis), {
    type: "MediaFeature",
    loc: this.getLocation(start, this.tokenStart),
    name,
    value
  };
}, MediaFeature$2.structure = structure$l;

var MediaQuery$2 = {};

const types$n = types$R;

MediaQuery$2.generate = function(node) {
  this.children(node);
}, MediaQuery$2.name = "MediaQuery", MediaQuery$2.parse = function() {
  const children = this.createList();
  let child = null;
  this.skipSC();
  scan: for (;!this.eof; ) {
    switch (this.tokenType) {
     case types$n.Comment:
     case types$n.WhiteSpace:
      this.next();
      continue;

     case types$n.Ident:
      child = this.Identifier();
      break;

     case types$n.LeftParenthesis:
      child = this.MediaFeature();
      break;

     default:
      break scan;
    }
    children.push(child);
  }
  return null === child && this.error("Identifier or parenthesis is expected"), {
    type: "MediaQuery",
    loc: this.getLocationFromList(children),
    children
  };
}, MediaQuery$2.structure = {
  children: [ [ "Identifier", "MediaFeature", "WhiteSpace" ] ]
};

var MediaQueryList$2 = {};

const types$m = types$R;

MediaQueryList$2.generate = function(node) {
  this.children(node, (() => this.token(types$m.Comma, ",")));
}, MediaQueryList$2.name = "MediaQueryList", MediaQueryList$2.parse = function() {
  const children = this.createList();
  for (this.skipSC(); !this.eof && (children.push(this.MediaQuery()), this.tokenType === types$m.Comma); ) this.next();
  return {
    type: "MediaQueryList",
    loc: this.getLocationFromList(children),
    children
  };
}, MediaQueryList$2.structure = {
  children: [ [ "MediaQuery" ] ]
};

var Nth$2 = {};

const types$l = types$R;

Nth$2.generate = function(node) {
  this.node(node.nth), null !== node.selector && (this.token(types$l.Ident, "of"), 
  this.node(node.selector));
}, Nth$2.name = "Nth", Nth$2.parse = function() {
  this.skipSC();
  const start = this.tokenStart;
  let nth, end = start, selector = null;
  return nth = this.lookupValue(0, "odd") || this.lookupValue(0, "even") ? this.Identifier() : this.AnPlusB(), 
  end = this.tokenStart, this.skipSC(), this.lookupValue(0, "of") && (this.next(), 
  selector = this.SelectorList(), end = this.tokenStart), {
    type: "Nth",
    loc: this.getLocation(start, end),
    nth,
    selector
  };
}, Nth$2.structure = {
  nth: [ "AnPlusB", "Identifier" ],
  selector: [ "SelectorList", null ]
};

var _Number$5 = {};

const types$k = types$R, structure$h = {
  value: String
};

_Number$5.generate = function(node) {
  this.token(types$k.Number, node.value);
}, _Number$5.name = "Number", _Number$5.parse = function() {
  return {
    type: "Number",
    loc: this.getLocation(this.tokenStart, this.tokenEnd),
    value: this.consume(types$k.Number)
  };
}, _Number$5.structure = structure$h;

var Operator$2 = {};

const structure$g = {
  value: String
};

Operator$2.generate = function(node) {
  this.tokenize(node.value);
}, Operator$2.name = "Operator", Operator$2.parse = function() {
  const start = this.tokenStart;
  return this.next(), {
    type: "Operator",
    loc: this.getLocation(start, this.tokenStart),
    value: this.substrToCursor(start)
  };
}, Operator$2.structure = structure$g;

var Parentheses$2 = {};

const types$j = types$R;

Parentheses$2.generate = function(node) {
  this.token(types$j.LeftParenthesis, "("), this.children(node), this.token(types$j.RightParenthesis, ")");
}, Parentheses$2.name = "Parentheses", Parentheses$2.parse = function(readSequence, recognizer) {
  const start = this.tokenStart;
  let children = null;
  return this.eat(types$j.LeftParenthesis), children = readSequence.call(this, recognizer), 
  this.eof || this.eat(types$j.RightParenthesis), {
    type: "Parentheses",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, Parentheses$2.structure = {
  children: [ [] ]
};

var Percentage$4 = {};

const types$i = types$R, structure$e = {
  value: String
};

Percentage$4.generate = function(node) {
  this.token(types$i.Percentage, node.value + "%");
}, Percentage$4.name = "Percentage", Percentage$4.parse = function() {
  return {
    type: "Percentage",
    loc: this.getLocation(this.tokenStart, this.tokenEnd),
    value: this.consumeNumber(types$i.Percentage)
  };
}, Percentage$4.structure = structure$e;

var PseudoClassSelector$2 = {};

const types$h = types$R, structure$d = {
  name: String,
  children: [ [ "Raw" ], null ]
};

PseudoClassSelector$2.generate = function(node) {
  this.token(types$h.Colon, ":"), null === node.children ? this.token(types$h.Ident, node.name) : (this.token(types$h.Function, node.name + "("), 
  this.children(node), this.token(types$h.RightParenthesis, ")"));
}, PseudoClassSelector$2.name = "PseudoClassSelector", PseudoClassSelector$2.parse = function() {
  const start = this.tokenStart;
  let name, nameLowerCase, children = null;
  return this.eat(types$h.Colon), this.tokenType === types$h.Function ? (name = this.consumeFunctionName(), 
  nameLowerCase = name.toLowerCase(), hasOwnProperty.call(this.pseudo, nameLowerCase) ? (this.skipSC(), 
  children = this.pseudo[nameLowerCase].call(this), this.skipSC()) : (children = this.createList(), 
  children.push(this.Raw(this.tokenIndex, null, !1))), this.eat(types$h.RightParenthesis)) : name = this.consume(types$h.Ident), 
  {
    type: "PseudoClassSelector",
    loc: this.getLocation(start, this.tokenStart),
    name,
    children
  };
}, PseudoClassSelector$2.structure = structure$d, PseudoClassSelector$2.walkContext = "function";

var PseudoElementSelector$2 = {};

const types$g = types$R, structure$c = {
  name: String,
  children: [ [ "Raw" ], null ]
};

PseudoElementSelector$2.generate = function(node) {
  this.token(types$g.Colon, ":"), this.token(types$g.Colon, ":"), null === node.children ? this.token(types$g.Ident, node.name) : (this.token(types$g.Function, node.name + "("), 
  this.children(node), this.token(types$g.RightParenthesis, ")"));
}, PseudoElementSelector$2.name = "PseudoElementSelector", PseudoElementSelector$2.parse = function() {
  const start = this.tokenStart;
  let name, nameLowerCase, children = null;
  return this.eat(types$g.Colon), this.eat(types$g.Colon), this.tokenType === types$g.Function ? (name = this.consumeFunctionName(), 
  nameLowerCase = name.toLowerCase(), hasOwnProperty.call(this.pseudo, nameLowerCase) ? (this.skipSC(), 
  children = this.pseudo[nameLowerCase].call(this), this.skipSC()) : (children = this.createList(), 
  children.push(this.Raw(this.tokenIndex, null, !1))), this.eat(types$g.RightParenthesis)) : name = this.consume(types$g.Ident), 
  {
    type: "PseudoElementSelector",
    loc: this.getLocation(start, this.tokenStart),
    name,
    children
  };
}, PseudoElementSelector$2.structure = structure$c, PseudoElementSelector$2.walkContext = "function";

var Ratio$2 = {};

const types$f = types$R, charCodeDefinitions$4 = charCodeDefinitions$c, FULLSTOP$1 = 0x002E;

function consumeNumber() {
  this.skipSC();
  const value = this.consume(types$f.Number);
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    charCodeDefinitions$4.isDigit(code) || code === FULLSTOP$1 || this.error("Unsigned number is expected", this.tokenStart - value.length + i);
  }
  return 0 === Number(value) && this.error("Zero number is not allowed", this.tokenStart - value.length), 
  value;
}

const structure$b = {
  left: String,
  right: String
};

Ratio$2.generate = function(node) {
  this.token(types$f.Number, node.left), this.token(types$f.Delim, "/"), this.token(types$f.Number, node.right);
}, Ratio$2.name = "Ratio", Ratio$2.parse = function() {
  const start = this.tokenStart, left = consumeNumber.call(this);
  let right;
  return this.skipSC(), this.eatDelim(47), right = consumeNumber.call(this), {
    type: "Ratio",
    loc: this.getLocation(start, this.tokenStart),
    left,
    right
  };
}, Ratio$2.structure = structure$b;

var Raw$4 = {};

const types$e = types$R;

function getOffsetExcludeWS() {
  return this.tokenIndex > 0 && this.lookupType(-1) === types$e.WhiteSpace ? this.tokenIndex > 1 ? this.getTokenStart(this.tokenIndex - 1) : this.firstCharOffset : this.tokenStart;
}

const structure$a = {
  value: String
};

Raw$4.generate = function(node) {
  this.tokenize(node.value);
}, Raw$4.name = "Raw", Raw$4.parse = function(startToken, consumeUntil, excludeWhiteSpace) {
  const startOffset = this.getTokenStart(startToken);
  let endOffset;
  return this.skipUntilBalanced(startToken, consumeUntil || this.consumeUntilBalanceEnd), 
  endOffset = excludeWhiteSpace && this.tokenStart > startOffset ? getOffsetExcludeWS.call(this) : this.tokenStart, 
  {
    type: "Raw",
    loc: this.getLocation(startOffset, endOffset),
    value: this.substring(startOffset, endOffset)
  };
}, Raw$4.structure = structure$a;

var Rule$4 = {};

const types$d = types$R;

function consumeRaw$2(startToken) {
  return this.Raw(startToken, this.consumeUntilLeftCurlyBracket, !0);
}

function consumePrelude() {
  const prelude = this.SelectorList();
  return "Raw" !== prelude.type && !1 === this.eof && this.tokenType !== types$d.LeftCurlyBracket && this.error(), 
  prelude;
}

Rule$4.generate = function(node) {
  this.node(node.prelude), this.node(node.block);
}, Rule$4.name = "Rule", Rule$4.parse = function() {
  const startToken = this.tokenIndex, startOffset = this.tokenStart;
  let prelude, block;
  return prelude = this.parseRulePrelude ? this.parseWithFallback(consumePrelude, consumeRaw$2) : consumeRaw$2.call(this, startToken), 
  block = this.Block(!0), {
    type: "Rule",
    loc: this.getLocation(startOffset, this.tokenStart),
    prelude,
    block
  };
}, Rule$4.structure = {
  prelude: [ "SelectorList", "Raw" ],
  block: [ "Block" ]
}, Rule$4.walkContext = "rule";

var Selector$3 = {};

Selector$3.generate = function(node) {
  this.children(node);
}, Selector$3.name = "Selector", Selector$3.parse = function() {
  const children = this.readSequence(this.scope.Selector);
  return null === this.getFirstListNode(children) && this.error("Selector is expected"), 
  {
    type: "Selector",
    loc: this.getLocationFromList(children),
    children
  };
}, Selector$3.structure = {
  children: [ [ "TypeSelector", "IdSelector", "ClassSelector", "AttributeSelector", "PseudoClassSelector", "PseudoElementSelector", "Combinator", "WhiteSpace" ] ]
};

var SelectorList$2 = {};

const types$c = types$R;

SelectorList$2.generate = function(node) {
  this.children(node, (() => this.token(types$c.Comma, ",")));
}, SelectorList$2.name = "SelectorList", SelectorList$2.parse = function() {
  const children = this.createList();
  for (;!this.eof && (children.push(this.Selector()), this.tokenType === types$c.Comma); ) this.next();
  return {
    type: "SelectorList",
    loc: this.getLocationFromList(children),
    children
  };
}, SelectorList$2.structure = {
  children: [ [ "Selector", "Raw" ] ]
}, SelectorList$2.walkContext = "selector";

var _String = {}, string$3 = {};

const charCodeDefinitions$3 = charCodeDefinitions$c, utils$d = utils$k;

string$3.decode = function(str) {
  const len = str.length, firstChar = str.charCodeAt(0), start = 34 === firstChar || 39 === firstChar ? 1 : 0, end = 1 === start && len > 1 && str.charCodeAt(len - 1) === firstChar ? len - 2 : len - 1;
  let decoded = "";
  for (let i = start; i <= end; i++) {
    let code = str.charCodeAt(i);
    if (92 === code) {
      if (i === end) {
        i !== len - 1 && (decoded = str.substr(i + 1));
        break;
      }
      if (code = str.charCodeAt(++i), charCodeDefinitions$3.isValidEscape(92, code)) {
        const escapeStart = i - 1, escapeEnd = utils$d.consumeEscaped(str, escapeStart);
        i = escapeEnd - 1, decoded += utils$d.decodeEscaped(str.substring(escapeStart + 1, escapeEnd));
      } else 0x000d === code && 0x000a === str.charCodeAt(i + 1) && i++;
    } else decoded += str[i];
  }
  return decoded;
}, string$3.encode = function(str, apostrophe) {
  const quote = apostrophe ? "'" : '"', quoteCode = apostrophe ? 39 : 34;
  let encoded = "", wsBeforeHexIsNeeded = !1;
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    0x0000 !== code ? code <= 0x001f || 0x007F === code ? (encoded += "\\" + code.toString(16), 
    wsBeforeHexIsNeeded = !0) : code === quoteCode || 92 === code ? (encoded += "\\" + str.charAt(i), 
    wsBeforeHexIsNeeded = !1) : (wsBeforeHexIsNeeded && (charCodeDefinitions$3.isHexDigit(code) || charCodeDefinitions$3.isWhiteSpace(code)) && (encoded += " "), 
    encoded += str.charAt(i), wsBeforeHexIsNeeded = !1) : encoded += "�";
  }
  return quote + encoded + quote;
};

const string$2 = string$3, types$b = types$R, structure$6 = {
  value: String
};

_String.generate = function(node) {
  this.token(types$b.String, string$2.encode(node.value));
}, _String.name = "String", _String.parse = function() {
  return {
    type: "String",
    loc: this.getLocation(this.tokenStart, this.tokenEnd),
    value: string$2.decode(this.consume(types$b.String))
  };
}, _String.structure = structure$6;

var StyleSheet$2 = {};

const types$a = types$R;

function consumeRaw$1(startToken) {
  return this.Raw(startToken, null, !1);
}

StyleSheet$2.generate = function(node) {
  this.children(node);
}, StyleSheet$2.name = "StyleSheet", StyleSheet$2.parse = function() {
  const start = this.tokenStart, children = this.createList();
  let child;
  for (;!this.eof; ) {
    switch (this.tokenType) {
     case types$a.WhiteSpace:
      this.next();
      continue;

     case types$a.Comment:
      if (33 !== this.charCodeAt(this.tokenStart + 2)) {
        this.next();
        continue;
      }
      child = this.Comment();
      break;

     case types$a.CDO:
      child = this.CDO();
      break;

     case types$a.CDC:
      child = this.CDC();
      break;

     case types$a.AtKeyword:
      child = this.parseWithFallback(this.Atrule, consumeRaw$1);
      break;

     default:
      child = this.parseWithFallback(this.Rule, consumeRaw$1);
    }
    children.push(child);
  }
  return {
    type: "StyleSheet",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, StyleSheet$2.structure = {
  children: [ [ "Comment", "CDO", "CDC", "Atrule", "Rule", "Raw" ] ]
}, StyleSheet$2.walkContext = "stylesheet";

var TypeSelector$4 = {};

const types$9 = types$R, ASTERISK$2 = 0x002A;

function eatIdentifierOrAsterisk() {
  this.tokenType !== types$9.Ident && !1 === this.isDelim(ASTERISK$2) && this.error("Identifier or asterisk is expected"), 
  this.next();
}

const structure$4 = {
  name: String
};

TypeSelector$4.generate = function(node) {
  this.tokenize(node.name);
}, TypeSelector$4.name = "TypeSelector", TypeSelector$4.parse = function() {
  const start = this.tokenStart;
  return this.isDelim(124) ? (this.next(), eatIdentifierOrAsterisk.call(this)) : (eatIdentifierOrAsterisk.call(this), 
  this.isDelim(124) && (this.next(), eatIdentifierOrAsterisk.call(this))), {
    type: "TypeSelector",
    loc: this.getLocation(start, this.tokenStart),
    name: this.substrToCursor(start)
  };
}, TypeSelector$4.structure = structure$4;

var UnicodeRange$2 = {};

const types$8 = types$R, charCodeDefinitions$2 = charCodeDefinitions$c, PLUSSIGN$2 = 0x002B, HYPHENMINUS$1 = 0x002D, QUESTIONMARK = 0x003F;

function eatHexSequence(offset, allowDash) {
  let len = 0;
  for (let pos = this.tokenStart + offset; pos < this.tokenEnd; pos++) {
    const code = this.charCodeAt(pos);
    if (code === HYPHENMINUS$1 && allowDash && 0 !== len) return eatHexSequence.call(this, offset + len + 1, !1), 
    -1;
    charCodeDefinitions$2.isHexDigit(code) || this.error(allowDash && 0 !== len ? "Hyphen minus" + (len < 6 ? " or hex digit" : "") + " is expected" : len < 6 ? "Hex digit is expected" : "Unexpected input", pos), 
    ++len > 6 && this.error("Too many hex digits", pos);
  }
  return this.next(), len;
}

function eatQuestionMarkSequence(max) {
  let count = 0;
  for (;this.isDelim(QUESTIONMARK); ) ++count > max && this.error("Too many question marks"), 
  this.next();
}

function startsWith(code) {
  this.charCodeAt(this.tokenStart) !== code && this.error((code === PLUSSIGN$2 ? "Plus sign" : "Hyphen minus") + " is expected");
}

function scanUnicodeRange() {
  let hexLength = 0;
  switch (this.tokenType) {
   case types$8.Number:
    if (hexLength = eatHexSequence.call(this, 1, !0), this.isDelim(QUESTIONMARK)) {
      eatQuestionMarkSequence.call(this, 6 - hexLength);
      break;
    }
    if (this.tokenType === types$8.Dimension || this.tokenType === types$8.Number) {
      startsWith.call(this, HYPHENMINUS$1), eatHexSequence.call(this, 1, !1);
      break;
    }
    break;

   case types$8.Dimension:
    hexLength = eatHexSequence.call(this, 1, !0), hexLength > 0 && eatQuestionMarkSequence.call(this, 6 - hexLength);
    break;

   default:
    if (this.eatDelim(PLUSSIGN$2), this.tokenType === types$8.Ident) {
      hexLength = eatHexSequence.call(this, 0, !0), hexLength > 0 && eatQuestionMarkSequence.call(this, 6 - hexLength);
      break;
    }
    if (this.isDelim(QUESTIONMARK)) {
      this.next(), eatQuestionMarkSequence.call(this, 5);
      break;
    }
    this.error("Hex digit or question mark is expected");
  }
}

const structure$3 = {
  value: String
};

UnicodeRange$2.generate = function(node) {
  this.tokenize(node.value);
}, UnicodeRange$2.name = "UnicodeRange", UnicodeRange$2.parse = function() {
  const start = this.tokenStart;
  return this.eatIdent("u"), scanUnicodeRange.call(this), {
    type: "UnicodeRange",
    loc: this.getLocation(start, this.tokenStart),
    value: this.substrToCursor(start)
  };
}, UnicodeRange$2.structure = structure$3;

var Url$4 = {}, url$2 = {};

const charCodeDefinitions$1 = charCodeDefinitions$c, utils$c = utils$k;

url$2.decode = function(str) {
  const len = str.length;
  let start = 4, end = 41 === str.charCodeAt(len - 1) ? len - 2 : len - 1, decoded = "";
  for (;start < end && charCodeDefinitions$1.isWhiteSpace(str.charCodeAt(start)); ) start++;
  for (;start < end && charCodeDefinitions$1.isWhiteSpace(str.charCodeAt(end)); ) end--;
  for (let i = start; i <= end; i++) {
    let code = str.charCodeAt(i);
    if (92 === code) {
      if (i === end) {
        i !== len - 1 && (decoded = str.substr(i + 1));
        break;
      }
      if (code = str.charCodeAt(++i), charCodeDefinitions$1.isValidEscape(92, code)) {
        const escapeStart = i - 1, escapeEnd = utils$c.consumeEscaped(str, escapeStart);
        i = escapeEnd - 1, decoded += utils$c.decodeEscaped(str.substring(escapeStart + 1, escapeEnd));
      } else 0x000d === code && 0x000a === str.charCodeAt(i + 1) && i++;
    } else decoded += str[i];
  }
  return decoded;
}, url$2.encode = function(str) {
  let encoded = "", wsBeforeHexIsNeeded = !1;
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    0x0000 !== code ? code <= 0x001f || 0x007F === code ? (encoded += "\\" + code.toString(16), 
    wsBeforeHexIsNeeded = !0) : 32 === code || 92 === code || 34 === code || 39 === code || 40 === code || 41 === code ? (encoded += "\\" + str.charAt(i), 
    wsBeforeHexIsNeeded = !1) : (wsBeforeHexIsNeeded && charCodeDefinitions$1.isHexDigit(code) && (encoded += " "), 
    encoded += str.charAt(i), wsBeforeHexIsNeeded = !1) : encoded += "�";
  }
  return "url(" + encoded + ")";
};

const url$1 = url$2, string$1 = string$3, types$7 = types$R, structure$2 = {
  value: String
};

Url$4.generate = function(node) {
  this.token(types$7.Url, url$1.encode(node.value));
}, Url$4.name = "Url", Url$4.parse = function() {
  const start = this.tokenStart;
  let value;
  switch (this.tokenType) {
   case types$7.Url:
    value = url$1.decode(this.consume(types$7.Url));
    break;

   case types$7.Function:
    this.cmpStr(this.tokenStart, this.tokenEnd, "url(") || this.error("Function name must be `url`"), 
    this.eat(types$7.Function), this.skipSC(), value = string$1.decode(this.consume(types$7.String)), 
    this.skipSC(), this.eof || this.eat(types$7.RightParenthesis);
    break;

   default:
    this.error("Url or Function is expected");
  }
  return {
    type: "Url",
    loc: this.getLocation(start, this.tokenStart),
    value
  };
}, Url$4.structure = structure$2;

var Value$4 = {};

Value$4.generate = function(node) {
  this.children(node);
}, Value$4.name = "Value", Value$4.parse = function() {
  const start = this.tokenStart, children = this.readSequence(this.scope.Value);
  return {
    type: "Value",
    loc: this.getLocation(start, this.tokenStart),
    children
  };
}, Value$4.structure = {
  children: [ [] ]
};

var WhiteSpace$4 = {};

const types$6 = types$R, SPACE = Object.freeze({
  type: "WhiteSpace",
  loc: null,
  value: " "
}), structure = {
  value: String
};

WhiteSpace$4.generate = function(node) {
  this.token(types$6.WhiteSpace, node.value);
}, WhiteSpace$4.name = "WhiteSpace", WhiteSpace$4.parse = function() {
  return this.eat(types$6.WhiteSpace), SPACE;
}, WhiteSpace$4.structure = structure;

const AnPlusB$1 = AnPlusB$2, Atrule$5 = Atrule$6, AtrulePrelude$1 = AtrulePrelude$2, AttributeSelector$3 = AttributeSelector$4, Block$1 = Block$2, Brackets$1 = Brackets$2, CDC$1 = CDC$2, CDO$1 = CDO$2, ClassSelector$1 = ClassSelector$2, Combinator$1 = Combinator$2, Comment$3 = Comment$4, Declaration$3 = Declaration$4, DeclarationList$1 = DeclarationList$2, Dimension$3 = Dimension$4, Function$2 = _Function, Hash$1 = Hash$2, Identifier$1 = Identifier$2, IdSelector$1 = IdSelector$2, MediaFeature$1 = MediaFeature$2, MediaQuery$1 = MediaQuery$2, MediaQueryList$1 = MediaQueryList$2, Nth$1 = Nth$2, Number$1$1 = _Number$5, Operator$1 = Operator$2, Parentheses$1 = Parentheses$2, Percentage$3 = Percentage$4, PseudoClassSelector$1 = PseudoClassSelector$2, PseudoElementSelector$1 = PseudoElementSelector$2, Ratio$1 = Ratio$2, Raw$3 = Raw$4, Rule$3 = Rule$4, Selector$2 = Selector$3, SelectorList$1 = SelectorList$2, String$1$1 = _String, StyleSheet$1 = StyleSheet$2, TypeSelector$3 = TypeSelector$4, UnicodeRange$1 = UnicodeRange$2, Url$3 = Url$4, Value$3 = Value$4, WhiteSpace$3 = WhiteSpace$4;

node.AnPlusB = AnPlusB$1, node.Atrule = Atrule$5, node.AtrulePrelude = AtrulePrelude$1, 
node.AttributeSelector = AttributeSelector$3, node.Block = Block$1, node.Brackets = Brackets$1, 
node.CDC = CDC$1, node.CDO = CDO$1, node.ClassSelector = ClassSelector$1, node.Combinator = Combinator$1, 
node.Comment = Comment$3, node.Declaration = Declaration$3, node.DeclarationList = DeclarationList$1, 
node.Dimension = Dimension$3, node.Function = Function$2, node.Hash = Hash$1, node.Identifier = Identifier$1, 
node.IdSelector = IdSelector$1, node.MediaFeature = MediaFeature$1, node.MediaQuery = MediaQuery$1, 
node.MediaQueryList = MediaQueryList$1, node.Nth = Nth$1, node.Number = Number$1$1, 
node.Operator = Operator$1, node.Parentheses = Parentheses$1, node.Percentage = Percentage$3, 
node.PseudoClassSelector = PseudoClassSelector$1, node.PseudoElementSelector = PseudoElementSelector$1, 
node.Ratio = Ratio$1, node.Raw = Raw$3, node.Rule = Rule$3, node.Selector = Selector$2, 
node.SelectorList = SelectorList$1, node.String = String$1$1, node.StyleSheet = StyleSheet$1, 
node.TypeSelector = TypeSelector$3, node.UnicodeRange = UnicodeRange$1, node.Url = Url$3, 
node.Value = Value$3, node.WhiteSpace = WhiteSpace$3;

var lexer$3 = {
  generic: !0,
  ...{
    "generic": !0,
    "types": {
      "absolute-size": "xx-small|x-small|small|medium|large|x-large|xx-large|xxx-large",
      "alpha-value": "<number>|<percentage>",
      "angle-percentage": "<angle>|<percentage>",
      "angular-color-hint": "<angle-percentage>",
      "angular-color-stop": "<color>&&<color-stop-angle>?",
      "angular-color-stop-list": "[<angular-color-stop> [, <angular-color-hint>]?]# , <angular-color-stop>",
      "animateable-feature": "scroll-position|contents|<custom-ident>",
      "attachment": "scroll|fixed|local",
      "attr()": "attr( <attr-name> <type-or-unit>? [, <attr-fallback>]? )",
      "attr-matcher": "['~'|'|'|'^'|'$'|'*']? '='",
      "attr-modifier": "i|s",
      "attribute-selector": "'[' <wq-name> ']'|'[' <wq-name> <attr-matcher> [<string-token>|<ident-token>] <attr-modifier>? ']'",
      "auto-repeat": "repeat( [auto-fill|auto-fit] , [<line-names>? <fixed-size>]+ <line-names>? )",
      "auto-track-list": "[<line-names>? [<fixed-size>|<fixed-repeat>]]* <line-names>? <auto-repeat> [<line-names>? [<fixed-size>|<fixed-repeat>]]* <line-names>?",
      "baseline-position": "[first|last]? baseline",
      "basic-shape": "<inset()>|<circle()>|<ellipse()>|<polygon()>|<path()>",
      "bg-image": "none|<image>",
      "bg-layer": "<bg-image>||<bg-position> [/ <bg-size>]?||<repeat-style>||<attachment>||<box>||<box>",
      "bg-position": "[[left|center|right|top|bottom|<length-percentage>]|[left|center|right|<length-percentage>] [top|center|bottom|<length-percentage>]|[center|[left|right] <length-percentage>?]&&[center|[top|bottom] <length-percentage>?]]",
      "bg-size": "[<length-percentage>|auto]{1,2}|cover|contain",
      "blur()": "blur( <length> )",
      "blend-mode": "normal|multiply|screen|overlay|darken|lighten|color-dodge|color-burn|hard-light|soft-light|difference|exclusion|hue|saturation|color|luminosity",
      "box": "border-box|padding-box|content-box",
      "brightness()": "brightness( <number-percentage> )",
      "calc()": "calc( <calc-sum> )",
      "calc-sum": "<calc-product> [['+'|'-'] <calc-product>]*",
      "calc-product": "<calc-value> ['*' <calc-value>|'/' <number>]*",
      "calc-value": "<number>|<dimension>|<percentage>|( <calc-sum> )",
      "cf-final-image": "<image>|<color>",
      "cf-mixing-image": "<percentage>?&&<image>",
      "circle()": "circle( [<shape-radius>]? [at <position>]? )",
      "clamp()": "clamp( <calc-sum>#{3} )",
      "class-selector": "'.' <ident-token>",
      "clip-source": "<url>",
      "color": "<rgb()>|<rgba()>|<hsl()>|<hsla()>|<hwb()>|<lab()>|<lch()>|<hex-color>|<named-color>|currentcolor|<deprecated-system-color>",
      "color-stop": "<color-stop-length>|<color-stop-angle>",
      "color-stop-angle": "<angle-percentage>{1,2}",
      "color-stop-length": "<length-percentage>{1,2}",
      "color-stop-list": "[<linear-color-stop> [, <linear-color-hint>]?]# , <linear-color-stop>",
      "combinator": "'>'|'+'|'~'|['||']",
      "common-lig-values": "[common-ligatures|no-common-ligatures]",
      "compat-auto": "searchfield|textarea|push-button|slider-horizontal|checkbox|radio|square-button|menulist|listbox|meter|progress-bar|button",
      "composite-style": "clear|copy|source-over|source-in|source-out|source-atop|destination-over|destination-in|destination-out|destination-atop|xor",
      "compositing-operator": "add|subtract|intersect|exclude",
      "compound-selector": "[<type-selector>? <subclass-selector>* [<pseudo-element-selector> <pseudo-class-selector>*]*]!",
      "compound-selector-list": "<compound-selector>#",
      "complex-selector": "<compound-selector> [<combinator>? <compound-selector>]*",
      "complex-selector-list": "<complex-selector>#",
      "conic-gradient()": "conic-gradient( [from <angle>]? [at <position>]? , <angular-color-stop-list> )",
      "contextual-alt-values": "[contextual|no-contextual]",
      "content-distribution": "space-between|space-around|space-evenly|stretch",
      "content-list": "[<string>|contents|<image>|<counter>|<quote>|<target>|<leader()>|<attr()>]+",
      "content-position": "center|start|end|flex-start|flex-end",
      "content-replacement": "<image>",
      "contrast()": "contrast( [<number-percentage>] )",
      "counter": "<counter()>|<counters()>",
      "counter()": "counter( <counter-name> , <counter-style>? )",
      "counter-name": "<custom-ident>",
      "counter-style": "<counter-style-name>|symbols( )",
      "counter-style-name": "<custom-ident>",
      "counters()": "counters( <counter-name> , <string> , <counter-style>? )",
      "cross-fade()": "cross-fade( <cf-mixing-image> , <cf-final-image>? )",
      "cubic-bezier-timing-function": "ease|ease-in|ease-out|ease-in-out|cubic-bezier( <number [0,1]> , <number> , <number [0,1]> , <number> )",
      "deprecated-system-color": "ActiveBorder|ActiveCaption|AppWorkspace|Background|ButtonFace|ButtonHighlight|ButtonShadow|ButtonText|CaptionText|GrayText|Highlight|HighlightText|InactiveBorder|InactiveCaption|InactiveCaptionText|InfoBackground|InfoText|Menu|MenuText|Scrollbar|ThreeDDarkShadow|ThreeDFace|ThreeDHighlight|ThreeDLightShadow|ThreeDShadow|Window|WindowFrame|WindowText",
      "discretionary-lig-values": "[discretionary-ligatures|no-discretionary-ligatures]",
      "display-box": "contents|none",
      "display-inside": "flow|flow-root|table|flex|grid|ruby",
      "display-internal": "table-row-group|table-header-group|table-footer-group|table-row|table-cell|table-column-group|table-column|table-caption|ruby-base|ruby-text|ruby-base-container|ruby-text-container",
      "display-legacy": "inline-block|inline-list-item|inline-table|inline-flex|inline-grid",
      "display-listitem": "<display-outside>?&&[flow|flow-root]?&&list-item",
      "display-outside": "block|inline|run-in",
      "drop-shadow()": "drop-shadow( <length>{2,3} <color>? )",
      "east-asian-variant-values": "[jis78|jis83|jis90|jis04|simplified|traditional]",
      "east-asian-width-values": "[full-width|proportional-width]",
      "element()": "element( <custom-ident> , [first|start|last|first-except]? )|element( <id-selector> )",
      "ellipse()": "ellipse( [<shape-radius>{2}]? [at <position>]? )",
      "ending-shape": "circle|ellipse",
      "env()": "env( <custom-ident> , <declaration-value>? )",
      "explicit-track-list": "[<line-names>? <track-size>]+ <line-names>?",
      "family-name": "<string>|<custom-ident>+",
      "feature-tag-value": "<string> [<integer>|on|off]?",
      "feature-type": "@stylistic|@historical-forms|@styleset|@character-variant|@swash|@ornaments|@annotation",
      "feature-value-block": "<feature-type> '{' <feature-value-declaration-list> '}'",
      "feature-value-block-list": "<feature-value-block>+",
      "feature-value-declaration": "<custom-ident> : <integer>+ ;",
      "feature-value-declaration-list": "<feature-value-declaration>",
      "feature-value-name": "<custom-ident>",
      "fill-rule": "nonzero|evenodd",
      "filter-function": "<blur()>|<brightness()>|<contrast()>|<drop-shadow()>|<grayscale()>|<hue-rotate()>|<invert()>|<opacity()>|<saturate()>|<sepia()>",
      "filter-function-list": "[<filter-function>|<url>]+",
      "final-bg-layer": "<'background-color'>||<bg-image>||<bg-position> [/ <bg-size>]?||<repeat-style>||<attachment>||<box>||<box>",
      "fit-content()": "fit-content( [<length>|<percentage>] )",
      "fixed-breadth": "<length-percentage>",
      "fixed-repeat": "repeat( [<integer [1,∞]>] , [<line-names>? <fixed-size>]+ <line-names>? )",
      "fixed-size": "<fixed-breadth>|minmax( <fixed-breadth> , <track-breadth> )|minmax( <inflexible-breadth> , <fixed-breadth> )",
      "font-stretch-absolute": "normal|ultra-condensed|extra-condensed|condensed|semi-condensed|semi-expanded|expanded|extra-expanded|ultra-expanded|<percentage>",
      "font-variant-css21": "[normal|small-caps]",
      "font-weight-absolute": "normal|bold|<number [1,1000]>",
      "frequency-percentage": "<frequency>|<percentage>",
      "general-enclosed": "[<function-token> <any-value> )]|( <ident> <any-value> )",
      "generic-family": "serif|sans-serif|cursive|fantasy|monospace|-apple-system",
      "generic-name": "serif|sans-serif|cursive|fantasy|monospace",
      "geometry-box": "<shape-box>|fill-box|stroke-box|view-box",
      "gradient": "<linear-gradient()>|<repeating-linear-gradient()>|<radial-gradient()>|<repeating-radial-gradient()>|<conic-gradient()>|<repeating-conic-gradient()>|<-legacy-gradient>",
      "grayscale()": "grayscale( <number-percentage> )",
      "grid-line": "auto|<custom-ident>|[<integer>&&<custom-ident>?]|[span&&[<integer>||<custom-ident>]]",
      "historical-lig-values": "[historical-ligatures|no-historical-ligatures]",
      "hsl()": "hsl( <hue> <percentage> <percentage> [/ <alpha-value>]? )|hsl( <hue> , <percentage> , <percentage> , <alpha-value>? )",
      "hsla()": "hsla( <hue> <percentage> <percentage> [/ <alpha-value>]? )|hsla( <hue> , <percentage> , <percentage> , <alpha-value>? )",
      "hue": "<number>|<angle>",
      "hue-rotate()": "hue-rotate( <angle> )",
      "hwb()": "hwb( [<hue>|none] [<percentage>|none] [<percentage>|none] [/ [<alpha-value>|none]]? )",
      "image": "<url>|<image()>|<image-set()>|<element()>|<paint()>|<cross-fade()>|<gradient>",
      "image()": "image( <image-tags>? [<image-src>? , <color>?]! )",
      "image-set()": "image-set( <image-set-option># )",
      "image-set-option": "[<image>|<string>] [<resolution>||type( <string> )]",
      "image-src": "<url>|<string>",
      "image-tags": "ltr|rtl",
      "inflexible-breadth": "<length>|<percentage>|min-content|max-content|auto",
      "inset()": "inset( <length-percentage>{1,4} [round <'border-radius'>]? )",
      "invert()": "invert( <number-percentage> )",
      "keyframes-name": "<custom-ident>|<string>",
      "keyframe-block": "<keyframe-selector># { <declaration-list> }",
      "keyframe-block-list": "<keyframe-block>+",
      "keyframe-selector": "from|to|<percentage>",
      "layer()": "layer( <layer-name> )",
      "layer-name": "<ident> ['.' <ident>]*",
      "leader()": "leader( <leader-type> )",
      "leader-type": "dotted|solid|space|<string>",
      "length-percentage": "<length>|<percentage>",
      "line-names": "'[' <custom-ident>* ']'",
      "line-name-list": "[<line-names>|<name-repeat>]+",
      "line-style": "none|hidden|dotted|dashed|solid|double|groove|ridge|inset|outset",
      "line-width": "<length>|thin|medium|thick",
      "linear-color-hint": "<length-percentage>",
      "linear-color-stop": "<color> <color-stop-length>?",
      "linear-gradient()": "linear-gradient( [<angle>|to <side-or-corner>]? , <color-stop-list> )",
      "mask-layer": "<mask-reference>||<position> [/ <bg-size>]?||<repeat-style>||<geometry-box>||[<geometry-box>|no-clip]||<compositing-operator>||<masking-mode>",
      "mask-position": "[<length-percentage>|left|center|right] [<length-percentage>|top|center|bottom]?",
      "mask-reference": "none|<image>|<mask-source>",
      "mask-source": "<url>",
      "masking-mode": "alpha|luminance|match-source",
      "matrix()": "matrix( <number>#{6} )",
      "matrix3d()": "matrix3d( <number>#{16} )",
      "max()": "max( <calc-sum># )",
      "media-and": "<media-in-parens> [and <media-in-parens>]+",
      "media-condition": "<media-not>|<media-and>|<media-or>|<media-in-parens>",
      "media-condition-without-or": "<media-not>|<media-and>|<media-in-parens>",
      "media-feature": "( [<mf-plain>|<mf-boolean>|<mf-range>] )",
      "media-in-parens": "( <media-condition> )|<media-feature>|<general-enclosed>",
      "media-not": "not <media-in-parens>",
      "media-or": "<media-in-parens> [or <media-in-parens>]+",
      "media-query": "<media-condition>|[not|only]? <media-type> [and <media-condition-without-or>]?",
      "media-query-list": "<media-query>#",
      "media-type": "<ident>",
      "mf-boolean": "<mf-name>",
      "mf-name": "<ident>",
      "mf-plain": "<mf-name> : <mf-value>",
      "mf-range": "<mf-name> ['<'|'>']? '='? <mf-value>|<mf-value> ['<'|'>']? '='? <mf-name>|<mf-value> '<' '='? <mf-name> '<' '='? <mf-value>|<mf-value> '>' '='? <mf-name> '>' '='? <mf-value>",
      "mf-value": "<number>|<dimension>|<ident>|<ratio>",
      "min()": "min( <calc-sum># )",
      "minmax()": "minmax( [<length>|<percentage>|min-content|max-content|auto] , [<length>|<percentage>|<flex>|min-content|max-content|auto] )",
      "name-repeat": "repeat( [<integer [1,∞]>|auto-fill] , <line-names>+ )",
      "named-color": "transparent|aliceblue|antiquewhite|aqua|aquamarine|azure|beige|bisque|black|blanchedalmond|blue|blueviolet|brown|burlywood|cadetblue|chartreuse|chocolate|coral|cornflowerblue|cornsilk|crimson|cyan|darkblue|darkcyan|darkgoldenrod|darkgray|darkgreen|darkgrey|darkkhaki|darkmagenta|darkolivegreen|darkorange|darkorchid|darkred|darksalmon|darkseagreen|darkslateblue|darkslategray|darkslategrey|darkturquoise|darkviolet|deeppink|deepskyblue|dimgray|dimgrey|dodgerblue|firebrick|floralwhite|forestgreen|fuchsia|gainsboro|ghostwhite|gold|goldenrod|gray|green|greenyellow|grey|honeydew|hotpink|indianred|indigo|ivory|khaki|lavender|lavenderblush|lawngreen|lemonchiffon|lightblue|lightcoral|lightcyan|lightgoldenrodyellow|lightgray|lightgreen|lightgrey|lightpink|lightsalmon|lightseagreen|lightskyblue|lightslategray|lightslategrey|lightsteelblue|lightyellow|lime|limegreen|linen|magenta|maroon|mediumaquamarine|mediumblue|mediumorchid|mediumpurple|mediumseagreen|mediumslateblue|mediumspringgreen|mediumturquoise|mediumvioletred|midnightblue|mintcream|mistyrose|moccasin|navajowhite|navy|oldlace|olive|olivedrab|orange|orangered|orchid|palegoldenrod|palegreen|paleturquoise|palevioletred|papayawhip|peachpuff|peru|pink|plum|powderblue|purple|rebeccapurple|red|rosybrown|royalblue|saddlebrown|salmon|sandybrown|seagreen|seashell|sienna|silver|skyblue|slateblue|slategray|slategrey|snow|springgreen|steelblue|tan|teal|thistle|tomato|turquoise|violet|wheat|white|whitesmoke|yellow|yellowgreen|<-non-standard-color>",
      "namespace-prefix": "<ident>",
      "ns-prefix": "[<ident-token>|'*']? '|'",
      "number-percentage": "<number>|<percentage>",
      "numeric-figure-values": "[lining-nums|oldstyle-nums]",
      "numeric-fraction-values": "[diagonal-fractions|stacked-fractions]",
      "numeric-spacing-values": "[proportional-nums|tabular-nums]",
      "nth": "<an-plus-b>|even|odd",
      "opacity()": "opacity( [<number-percentage>] )",
      "overflow-position": "unsafe|safe",
      "outline-radius": "<length>|<percentage>",
      "page-body": "<declaration>? [; <page-body>]?|<page-margin-box> <page-body>",
      "page-margin-box": "<page-margin-box-type> '{' <declaration-list> '}'",
      "page-margin-box-type": "@top-left-corner|@top-left|@top-center|@top-right|@top-right-corner|@bottom-left-corner|@bottom-left|@bottom-center|@bottom-right|@bottom-right-corner|@left-top|@left-middle|@left-bottom|@right-top|@right-middle|@right-bottom",
      "page-selector-list": "[<page-selector>#]?",
      "page-selector": "<pseudo-page>+|<ident> <pseudo-page>*",
      "page-size": "A5|A4|A3|B5|B4|JIS-B5|JIS-B4|letter|legal|ledger",
      "path()": "path( [<fill-rule> ,]? <string> )",
      "paint()": "paint( <ident> , <declaration-value>? )",
      "perspective()": "perspective( <length> )",
      "polygon()": "polygon( <fill-rule>? , [<length-percentage> <length-percentage>]# )",
      "position": "[[left|center|right]||[top|center|bottom]|[left|center|right|<length-percentage>] [top|center|bottom|<length-percentage>]?|[[left|right] <length-percentage>]&&[[top|bottom] <length-percentage>]]",
      "pseudo-class-selector": "':' <ident-token>|':' <function-token> <any-value> ')'",
      "pseudo-element-selector": "':' <pseudo-class-selector>",
      "pseudo-page": ": [left|right|first|blank]",
      "quote": "open-quote|close-quote|no-open-quote|no-close-quote",
      "radial-gradient()": "radial-gradient( [<ending-shape>||<size>]? [at <position>]? , <color-stop-list> )",
      "relative-selector": "<combinator>? <complex-selector>",
      "relative-selector-list": "<relative-selector>#",
      "relative-size": "larger|smaller",
      "repeat-style": "repeat-x|repeat-y|[repeat|space|round|no-repeat]{1,2}",
      "repeating-conic-gradient()": "repeating-conic-gradient( [from <angle>]? [at <position>]? , <angular-color-stop-list> )",
      "repeating-linear-gradient()": "repeating-linear-gradient( [<angle>|to <side-or-corner>]? , <color-stop-list> )",
      "repeating-radial-gradient()": "repeating-radial-gradient( [<ending-shape>||<size>]? [at <position>]? , <color-stop-list> )",
      "rgb()": "rgb( <percentage>{3} [/ <alpha-value>]? )|rgb( <number>{3} [/ <alpha-value>]? )|rgb( <percentage>#{3} , <alpha-value>? )|rgb( <number>#{3} , <alpha-value>? )",
      "rgba()": "rgba( <percentage>{3} [/ <alpha-value>]? )|rgba( <number>{3} [/ <alpha-value>]? )|rgba( <percentage>#{3} , <alpha-value>? )|rgba( <number>#{3} , <alpha-value>? )",
      "rotate()": "rotate( [<angle>|<zero>] )",
      "rotate3d()": "rotate3d( <number> , <number> , <number> , [<angle>|<zero>] )",
      "rotateX()": "rotateX( [<angle>|<zero>] )",
      "rotateY()": "rotateY( [<angle>|<zero>] )",
      "rotateZ()": "rotateZ( [<angle>|<zero>] )",
      "saturate()": "saturate( <number-percentage> )",
      "scale()": "scale( <number> , <number>? )",
      "scale3d()": "scale3d( <number> , <number> , <number> )",
      "scaleX()": "scaleX( <number> )",
      "scaleY()": "scaleY( <number> )",
      "scaleZ()": "scaleZ( <number> )",
      "self-position": "center|start|end|self-start|self-end|flex-start|flex-end",
      "shape-radius": "<length-percentage>|closest-side|farthest-side",
      "skew()": "skew( [<angle>|<zero>] , [<angle>|<zero>]? )",
      "skewX()": "skewX( [<angle>|<zero>] )",
      "skewY()": "skewY( [<angle>|<zero>] )",
      "sepia()": "sepia( <number-percentage> )",
      "shadow": "inset?&&<length>{2,4}&&<color>?",
      "shadow-t": "[<length>{2,3}&&<color>?]",
      "shape": "rect( <top> , <right> , <bottom> , <left> )|rect( <top> <right> <bottom> <left> )",
      "shape-box": "<box>|margin-box",
      "side-or-corner": "[left|right]||[top|bottom]",
      "single-animation": "<time>||<easing-function>||<time>||<single-animation-iteration-count>||<single-animation-direction>||<single-animation-fill-mode>||<single-animation-play-state>||[none|<keyframes-name>]",
      "single-animation-direction": "normal|reverse|alternate|alternate-reverse",
      "single-animation-fill-mode": "none|forwards|backwards|both",
      "single-animation-iteration-count": "infinite|<number>",
      "single-animation-play-state": "running|paused",
      "single-animation-timeline": "auto|none|<timeline-name>",
      "single-transition": "[none|<single-transition-property>]||<time>||<easing-function>||<time>",
      "single-transition-property": "all|<custom-ident>",
      "size": "closest-side|farthest-side|closest-corner|farthest-corner|<length>|<length-percentage>{2}",
      "step-position": "jump-start|jump-end|jump-none|jump-both|start|end",
      "step-timing-function": "step-start|step-end|steps( <integer> [, <step-position>]? )",
      "subclass-selector": "<id-selector>|<class-selector>|<attribute-selector>|<pseudo-class-selector>",
      "supports-condition": "not <supports-in-parens>|<supports-in-parens> [and <supports-in-parens>]*|<supports-in-parens> [or <supports-in-parens>]*",
      "supports-in-parens": "( <supports-condition> )|<supports-feature>|<general-enclosed>",
      "supports-feature": "<supports-decl>|<supports-selector-fn>",
      "supports-decl": "( <declaration> )",
      "supports-selector-fn": "selector( <complex-selector> )",
      "symbol": "<string>|<image>|<custom-ident>",
      "target": "<target-counter()>|<target-counters()>|<target-text()>",
      "target-counter()": "target-counter( [<string>|<url>] , <custom-ident> , <counter-style>? )",
      "target-counters()": "target-counters( [<string>|<url>] , <custom-ident> , <string> , <counter-style>? )",
      "target-text()": "target-text( [<string>|<url>] , [content|before|after|first-letter]? )",
      "time-percentage": "<time>|<percentage>",
      "timeline-name": "<custom-ident>|<string>",
      "easing-function": "linear|<cubic-bezier-timing-function>|<step-timing-function>",
      "track-breadth": "<length-percentage>|<flex>|min-content|max-content|auto",
      "track-list": "[<line-names>? [<track-size>|<track-repeat>]]+ <line-names>?",
      "track-repeat": "repeat( [<integer [1,∞]>] , [<line-names>? <track-size>]+ <line-names>? )",
      "track-size": "<track-breadth>|minmax( <inflexible-breadth> , <track-breadth> )|fit-content( [<length>|<percentage>] )",
      "transform-function": "<matrix()>|<translate()>|<translateX()>|<translateY()>|<scale()>|<scaleX()>|<scaleY()>|<rotate()>|<skew()>|<skewX()>|<skewY()>|<matrix3d()>|<translate3d()>|<translateZ()>|<scale3d()>|<scaleZ()>|<rotate3d()>|<rotateX()>|<rotateY()>|<rotateZ()>|<perspective()>",
      "transform-list": "<transform-function>+",
      "translate()": "translate( <length-percentage> , <length-percentage>? )",
      "translate3d()": "translate3d( <length-percentage> , <length-percentage> , <length> )",
      "translateX()": "translateX( <length-percentage> )",
      "translateY()": "translateY( <length-percentage> )",
      "translateZ()": "translateZ( <length> )",
      "type-or-unit": "string|color|url|integer|number|length|angle|time|frequency|cap|ch|em|ex|ic|lh|rlh|rem|vb|vi|vw|vh|vmin|vmax|mm|Q|cm|in|pt|pc|px|deg|grad|rad|turn|ms|s|Hz|kHz|%",
      "type-selector": "<wq-name>|<ns-prefix>? '*'",
      "var()": "var( <custom-property-name> , <declaration-value>? )",
      "viewport-length": "auto|<length-percentage>",
      "visual-box": "content-box|padding-box|border-box",
      "wq-name": "<ns-prefix>? <ident-token>",
      "-legacy-gradient": "<-webkit-gradient()>|<-legacy-linear-gradient>|<-legacy-repeating-linear-gradient>|<-legacy-radial-gradient>|<-legacy-repeating-radial-gradient>",
      "-legacy-linear-gradient": "-moz-linear-gradient( <-legacy-linear-gradient-arguments> )|-webkit-linear-gradient( <-legacy-linear-gradient-arguments> )|-o-linear-gradient( <-legacy-linear-gradient-arguments> )",
      "-legacy-repeating-linear-gradient": "-moz-repeating-linear-gradient( <-legacy-linear-gradient-arguments> )|-webkit-repeating-linear-gradient( <-legacy-linear-gradient-arguments> )|-o-repeating-linear-gradient( <-legacy-linear-gradient-arguments> )",
      "-legacy-linear-gradient-arguments": "[<angle>|<side-or-corner>]? , <color-stop-list>",
      "-legacy-radial-gradient": "-moz-radial-gradient( <-legacy-radial-gradient-arguments> )|-webkit-radial-gradient( <-legacy-radial-gradient-arguments> )|-o-radial-gradient( <-legacy-radial-gradient-arguments> )",
      "-legacy-repeating-radial-gradient": "-moz-repeating-radial-gradient( <-legacy-radial-gradient-arguments> )|-webkit-repeating-radial-gradient( <-legacy-radial-gradient-arguments> )|-o-repeating-radial-gradient( <-legacy-radial-gradient-arguments> )",
      "-legacy-radial-gradient-arguments": "[<position> ,]? [[[<-legacy-radial-gradient-shape>||<-legacy-radial-gradient-size>]|[<length>|<percentage>]{2}] ,]? <color-stop-list>",
      "-legacy-radial-gradient-size": "closest-side|closest-corner|farthest-side|farthest-corner|contain|cover",
      "-legacy-radial-gradient-shape": "circle|ellipse",
      "-non-standard-font": "-apple-system-body|-apple-system-headline|-apple-system-subheadline|-apple-system-caption1|-apple-system-caption2|-apple-system-footnote|-apple-system-short-body|-apple-system-short-headline|-apple-system-short-subheadline|-apple-system-short-caption1|-apple-system-short-footnote|-apple-system-tall-body",
      "-non-standard-color": "-moz-ButtonDefault|-moz-ButtonHoverFace|-moz-ButtonHoverText|-moz-CellHighlight|-moz-CellHighlightText|-moz-Combobox|-moz-ComboboxText|-moz-Dialog|-moz-DialogText|-moz-dragtargetzone|-moz-EvenTreeRow|-moz-Field|-moz-FieldText|-moz-html-CellHighlight|-moz-html-CellHighlightText|-moz-mac-accentdarkestshadow|-moz-mac-accentdarkshadow|-moz-mac-accentface|-moz-mac-accentlightesthighlight|-moz-mac-accentlightshadow|-moz-mac-accentregularhighlight|-moz-mac-accentregularshadow|-moz-mac-chrome-active|-moz-mac-chrome-inactive|-moz-mac-focusring|-moz-mac-menuselect|-moz-mac-menushadow|-moz-mac-menutextselect|-moz-MenuHover|-moz-MenuHoverText|-moz-MenuBarText|-moz-MenuBarHoverText|-moz-nativehyperlinktext|-moz-OddTreeRow|-moz-win-communicationstext|-moz-win-mediatext|-moz-activehyperlinktext|-moz-default-background-color|-moz-default-color|-moz-hyperlinktext|-moz-visitedhyperlinktext|-webkit-activelink|-webkit-focus-ring-color|-webkit-link|-webkit-text",
      "-non-standard-image-rendering": "optimize-contrast|-moz-crisp-edges|-o-crisp-edges|-webkit-optimize-contrast",
      "-non-standard-overflow": "-moz-scrollbars-none|-moz-scrollbars-horizontal|-moz-scrollbars-vertical|-moz-hidden-unscrollable",
      "-non-standard-width": "fill-available|min-intrinsic|intrinsic|-moz-available|-moz-fit-content|-moz-min-content|-moz-max-content|-webkit-min-content|-webkit-max-content",
      "-webkit-gradient()": "-webkit-gradient( <-webkit-gradient-type> , <-webkit-gradient-point> [, <-webkit-gradient-point>|, <-webkit-gradient-radius> , <-webkit-gradient-point>] [, <-webkit-gradient-radius>]? [, <-webkit-gradient-color-stop>]* )",
      "-webkit-gradient-color-stop": "from( <color> )|color-stop( [<number-zero-one>|<percentage>] , <color> )|to( <color> )",
      "-webkit-gradient-point": "[left|center|right|<length-percentage>] [top|center|bottom|<length-percentage>]",
      "-webkit-gradient-radius": "<length>|<percentage>",
      "-webkit-gradient-type": "linear|radial",
      "-webkit-mask-box-repeat": "repeat|stretch|round",
      "-webkit-mask-clip-style": "border|border-box|padding|padding-box|content|content-box|text",
      "-ms-filter-function-list": "<-ms-filter-function>+",
      "-ms-filter-function": "<-ms-filter-function-progid>|<-ms-filter-function-legacy>",
      "-ms-filter-function-progid": "'progid:' [<ident-token> '.']* [<ident-token>|<function-token> <any-value>? )]",
      "-ms-filter-function-legacy": "<ident-token>|<function-token> <any-value>? )",
      "-ms-filter": "<string>",
      "age": "child|young|old",
      "attr-name": "<wq-name>",
      "attr-fallback": "<any-value>",
      "bg-clip": "<box>|border|text",
      "border-radius": "<length-percentage>{1,2}",
      "bottom": "<length>|auto",
      "generic-voice": "[<age>? <gender> <integer>?]",
      "gender": "male|female|neutral",
      "lab()": "lab( [<percentage>|<number>|none] [<percentage>|<number>|none] [<percentage>|<number>|none] [/ [<alpha-value>|none]]? )",
      "lch()": "lch( [<percentage>|<number>|none] [<percentage>|<number>|none] [<hue>|none] [/ [<alpha-value>|none]]? )",
      "left": "<length>|auto",
      "mask-image": "<mask-reference>#",
      "paint": "none|<color>|<url> [none|<color>]?|context-fill|context-stroke",
      "ratio": "<number [0,∞]> [/ <number [0,∞]>]?",
      "reversed-counter-name": "reversed( <counter-name> )",
      "right": "<length>|auto",
      "svg-length": "<percentage>|<length>|<number>",
      "svg-writing-mode": "lr-tb|rl-tb|tb-rl|lr|rl|tb",
      "top": "<length>|auto",
      "track-group": "'(' [<string>* <track-minmax> <string>*]+ ')' ['[' <positive-integer> ']']?|<track-minmax>",
      "track-list-v0": "[<string>* <track-group> <string>*]+|none",
      "track-minmax": "minmax( <track-breadth> , <track-breadth> )|auto|<track-breadth>|fit-content",
      "x": "<number>",
      "y": "<number>",
      "declaration": "<ident-token> : <declaration-value>? ['!' important]?",
      "declaration-list": "[<declaration>? ';']* <declaration>?",
      "url": "url( <string> <url-modifier>* )|<url-token>",
      "url-modifier": "<ident>|<function-token> <any-value> )",
      "number-zero-one": "<number [0,1]>",
      "number-one-or-greater": "<number [1,∞]>",
      "positive-integer": "<integer [0,∞]>",
      "-non-standard-display": "-ms-inline-flexbox|-ms-grid|-ms-inline-grid|-webkit-flex|-webkit-inline-flex|-webkit-box|-webkit-inline-box|-moz-inline-stack|-moz-box|-moz-inline-box"
    },
    "properties": {
      "--*": "<declaration-value>",
      "-ms-accelerator": "false|true",
      "-ms-block-progression": "tb|rl|bt|lr",
      "-ms-content-zoom-chaining": "none|chained",
      "-ms-content-zooming": "none|zoom",
      "-ms-content-zoom-limit": "<'-ms-content-zoom-limit-min'> <'-ms-content-zoom-limit-max'>",
      "-ms-content-zoom-limit-max": "<percentage>",
      "-ms-content-zoom-limit-min": "<percentage>",
      "-ms-content-zoom-snap": "<'-ms-content-zoom-snap-type'>||<'-ms-content-zoom-snap-points'>",
      "-ms-content-zoom-snap-points": "snapInterval( <percentage> , <percentage> )|snapList( <percentage># )",
      "-ms-content-zoom-snap-type": "none|proximity|mandatory",
      "-ms-filter": "<string>",
      "-ms-flow-from": "[none|<custom-ident>]#",
      "-ms-flow-into": "[none|<custom-ident>]#",
      "-ms-grid-columns": "none|<track-list>|<auto-track-list>",
      "-ms-grid-rows": "none|<track-list>|<auto-track-list>",
      "-ms-high-contrast-adjust": "auto|none",
      "-ms-hyphenate-limit-chars": "auto|<integer>{1,3}",
      "-ms-hyphenate-limit-lines": "no-limit|<integer>",
      "-ms-hyphenate-limit-zone": "<percentage>|<length>",
      "-ms-ime-align": "auto|after",
      "-ms-overflow-style": "auto|none|scrollbar|-ms-autohiding-scrollbar",
      "-ms-scrollbar-3dlight-color": "<color>",
      "-ms-scrollbar-arrow-color": "<color>",
      "-ms-scrollbar-base-color": "<color>",
      "-ms-scrollbar-darkshadow-color": "<color>",
      "-ms-scrollbar-face-color": "<color>",
      "-ms-scrollbar-highlight-color": "<color>",
      "-ms-scrollbar-shadow-color": "<color>",
      "-ms-scrollbar-track-color": "<color>",
      "-ms-scroll-chaining": "chained|none",
      "-ms-scroll-limit": "<'-ms-scroll-limit-x-min'> <'-ms-scroll-limit-y-min'> <'-ms-scroll-limit-x-max'> <'-ms-scroll-limit-y-max'>",
      "-ms-scroll-limit-x-max": "auto|<length>",
      "-ms-scroll-limit-x-min": "<length>",
      "-ms-scroll-limit-y-max": "auto|<length>",
      "-ms-scroll-limit-y-min": "<length>",
      "-ms-scroll-rails": "none|railed",
      "-ms-scroll-snap-points-x": "snapInterval( <length-percentage> , <length-percentage> )|snapList( <length-percentage># )",
      "-ms-scroll-snap-points-y": "snapInterval( <length-percentage> , <length-percentage> )|snapList( <length-percentage># )",
      "-ms-scroll-snap-type": "none|proximity|mandatory",
      "-ms-scroll-snap-x": "<'-ms-scroll-snap-type'> <'-ms-scroll-snap-points-x'>",
      "-ms-scroll-snap-y": "<'-ms-scroll-snap-type'> <'-ms-scroll-snap-points-y'>",
      "-ms-scroll-translation": "none|vertical-to-horizontal",
      "-ms-text-autospace": "none|ideograph-alpha|ideograph-numeric|ideograph-parenthesis|ideograph-space",
      "-ms-touch-select": "grippers|none",
      "-ms-user-select": "none|element|text",
      "-ms-wrap-flow": "auto|both|start|end|maximum|clear",
      "-ms-wrap-margin": "<length>",
      "-ms-wrap-through": "wrap|none",
      "-moz-appearance": "none|button|button-arrow-down|button-arrow-next|button-arrow-previous|button-arrow-up|button-bevel|button-focus|caret|checkbox|checkbox-container|checkbox-label|checkmenuitem|dualbutton|groupbox|listbox|listitem|menuarrow|menubar|menucheckbox|menuimage|menuitem|menuitemtext|menulist|menulist-button|menulist-text|menulist-textfield|menupopup|menuradio|menuseparator|meterbar|meterchunk|progressbar|progressbar-vertical|progresschunk|progresschunk-vertical|radio|radio-container|radio-label|radiomenuitem|range|range-thumb|resizer|resizerpanel|scale-horizontal|scalethumbend|scalethumb-horizontal|scalethumbstart|scalethumbtick|scalethumb-vertical|scale-vertical|scrollbarbutton-down|scrollbarbutton-left|scrollbarbutton-right|scrollbarbutton-up|scrollbarthumb-horizontal|scrollbarthumb-vertical|scrollbartrack-horizontal|scrollbartrack-vertical|searchfield|separator|sheet|spinner|spinner-downbutton|spinner-textfield|spinner-upbutton|splitter|statusbar|statusbarpanel|tab|tabpanel|tabpanels|tab-scroll-arrow-back|tab-scroll-arrow-forward|textfield|textfield-multiline|toolbar|toolbarbutton|toolbarbutton-dropdown|toolbargripper|toolbox|tooltip|treeheader|treeheadercell|treeheadersortarrow|treeitem|treeline|treetwisty|treetwistyopen|treeview|-moz-mac-unified-toolbar|-moz-win-borderless-glass|-moz-win-browsertabbar-toolbox|-moz-win-communicationstext|-moz-win-communications-toolbox|-moz-win-exclude-glass|-moz-win-glass|-moz-win-mediatext|-moz-win-media-toolbox|-moz-window-button-box|-moz-window-button-box-maximized|-moz-window-button-close|-moz-window-button-maximize|-moz-window-button-minimize|-moz-window-button-restore|-moz-window-frame-bottom|-moz-window-frame-left|-moz-window-frame-right|-moz-window-titlebar|-moz-window-titlebar-maximized",
      "-moz-binding": "<url>|none",
      "-moz-border-bottom-colors": "<color>+|none",
      "-moz-border-left-colors": "<color>+|none",
      "-moz-border-right-colors": "<color>+|none",
      "-moz-border-top-colors": "<color>+|none",
      "-moz-context-properties": "none|[fill|fill-opacity|stroke|stroke-opacity]#",
      "-moz-float-edge": "border-box|content-box|margin-box|padding-box",
      "-moz-force-broken-image-icon": "0|1",
      "-moz-image-region": "<shape>|auto",
      "-moz-orient": "inline|block|horizontal|vertical",
      "-moz-outline-radius": "<outline-radius>{1,4} [/ <outline-radius>{1,4}]?",
      "-moz-outline-radius-bottomleft": "<outline-radius>",
      "-moz-outline-radius-bottomright": "<outline-radius>",
      "-moz-outline-radius-topleft": "<outline-radius>",
      "-moz-outline-radius-topright": "<outline-radius>",
      "-moz-stack-sizing": "ignore|stretch-to-fit",
      "-moz-text-blink": "none|blink",
      "-moz-user-focus": "ignore|normal|select-after|select-before|select-menu|select-same|select-all|none",
      "-moz-user-input": "auto|none|enabled|disabled",
      "-moz-user-modify": "read-only|read-write|write-only",
      "-moz-window-dragging": "drag|no-drag",
      "-moz-window-shadow": "default|menu|tooltip|sheet|none",
      "-webkit-appearance": "none|button|button-bevel|caps-lock-indicator|caret|checkbox|default-button|inner-spin-button|listbox|listitem|media-controls-background|media-controls-fullscreen-background|media-current-time-display|media-enter-fullscreen-button|media-exit-fullscreen-button|media-fullscreen-button|media-mute-button|media-overlay-play-button|media-play-button|media-seek-back-button|media-seek-forward-button|media-slider|media-sliderthumb|media-time-remaining-display|media-toggle-closed-captions-button|media-volume-slider|media-volume-slider-container|media-volume-sliderthumb|menulist|menulist-button|menulist-text|menulist-textfield|meter|progress-bar|progress-bar-value|push-button|radio|scrollbarbutton-down|scrollbarbutton-left|scrollbarbutton-right|scrollbarbutton-up|scrollbargripper-horizontal|scrollbargripper-vertical|scrollbarthumb-horizontal|scrollbarthumb-vertical|scrollbartrack-horizontal|scrollbartrack-vertical|searchfield|searchfield-cancel-button|searchfield-decoration|searchfield-results-button|searchfield-results-decoration|slider-horizontal|slider-vertical|sliderthumb-horizontal|sliderthumb-vertical|square-button|textarea|textfield|-apple-pay-button",
      "-webkit-border-before": "<'border-width'>||<'border-style'>||<color>",
      "-webkit-border-before-color": "<color>",
      "-webkit-border-before-style": "<'border-style'>",
      "-webkit-border-before-width": "<'border-width'>",
      "-webkit-box-reflect": "[above|below|right|left]? <length>? <image>?",
      "-webkit-line-clamp": "none|<integer>",
      "-webkit-mask": "[<mask-reference>||<position> [/ <bg-size>]?||<repeat-style>||[<box>|border|padding|content|text]||[<box>|border|padding|content]]#",
      "-webkit-mask-attachment": "<attachment>#",
      "-webkit-mask-clip": "[<box>|border|padding|content|text]#",
      "-webkit-mask-composite": "<composite-style>#",
      "-webkit-mask-image": "<mask-reference>#",
      "-webkit-mask-origin": "[<box>|border|padding|content]#",
      "-webkit-mask-position": "<position>#",
      "-webkit-mask-position-x": "[<length-percentage>|left|center|right]#",
      "-webkit-mask-position-y": "[<length-percentage>|top|center|bottom]#",
      "-webkit-mask-repeat": "<repeat-style>#",
      "-webkit-mask-repeat-x": "repeat|no-repeat|space|round",
      "-webkit-mask-repeat-y": "repeat|no-repeat|space|round",
      "-webkit-mask-size": "<bg-size>#",
      "-webkit-overflow-scrolling": "auto|touch",
      "-webkit-tap-highlight-color": "<color>",
      "-webkit-text-fill-color": "<color>",
      "-webkit-text-stroke": "<length>||<color>",
      "-webkit-text-stroke-color": "<color>",
      "-webkit-text-stroke-width": "<length>",
      "-webkit-touch-callout": "default|none",
      "-webkit-user-modify": "read-only|read-write|read-write-plaintext-only",
      "accent-color": "auto|<color>",
      "align-content": "normal|<baseline-position>|<content-distribution>|<overflow-position>? <content-position>",
      "align-items": "normal|stretch|<baseline-position>|[<overflow-position>? <self-position>]",
      "align-self": "auto|normal|stretch|<baseline-position>|<overflow-position>? <self-position>",
      "align-tracks": "[normal|<baseline-position>|<content-distribution>|<overflow-position>? <content-position>]#",
      "all": "initial|inherit|unset|revert|revert-layer",
      "animation": "<single-animation>#",
      "animation-delay": "<time>#",
      "animation-direction": "<single-animation-direction>#",
      "animation-duration": "<time>#",
      "animation-fill-mode": "<single-animation-fill-mode>#",
      "animation-iteration-count": "<single-animation-iteration-count>#",
      "animation-name": "[none|<keyframes-name>]#",
      "animation-play-state": "<single-animation-play-state>#",
      "animation-timing-function": "<easing-function>#",
      "animation-timeline": "<single-animation-timeline>#",
      "appearance": "none|auto|textfield|menulist-button|<compat-auto>",
      "aspect-ratio": "auto|<ratio>",
      "azimuth": "<angle>|[[left-side|far-left|left|center-left|center|center-right|right|far-right|right-side]||behind]|leftwards|rightwards",
      "backdrop-filter": "none|<filter-function-list>",
      "backface-visibility": "visible|hidden",
      "background": "[<bg-layer> ,]* <final-bg-layer>",
      "background-attachment": "<attachment>#",
      "background-blend-mode": "<blend-mode>#",
      "background-clip": "<bg-clip>#",
      "background-color": "<color>",
      "background-image": "<bg-image>#",
      "background-origin": "<box>#",
      "background-position": "<bg-position>#",
      "background-position-x": "[center|[[left|right|x-start|x-end]? <length-percentage>?]!]#",
      "background-position-y": "[center|[[top|bottom|y-start|y-end]? <length-percentage>?]!]#",
      "background-repeat": "<repeat-style>#",
      "background-size": "<bg-size>#",
      "block-overflow": "clip|ellipsis|<string>",
      "block-size": "<'width'>",
      "border": "<line-width>||<line-style>||<color>",
      "border-block": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-block-color": "<'border-top-color'>{1,2}",
      "border-block-style": "<'border-top-style'>",
      "border-block-width": "<'border-top-width'>",
      "border-block-end": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-block-end-color": "<'border-top-color'>",
      "border-block-end-style": "<'border-top-style'>",
      "border-block-end-width": "<'border-top-width'>",
      "border-block-start": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-block-start-color": "<'border-top-color'>",
      "border-block-start-style": "<'border-top-style'>",
      "border-block-start-width": "<'border-top-width'>",
      "border-bottom": "<line-width>||<line-style>||<color>",
      "border-bottom-color": "<'border-top-color'>",
      "border-bottom-left-radius": "<length-percentage>{1,2}",
      "border-bottom-right-radius": "<length-percentage>{1,2}",
      "border-bottom-style": "<line-style>",
      "border-bottom-width": "<line-width>",
      "border-collapse": "collapse|separate",
      "border-color": "<color>{1,4}",
      "border-end-end-radius": "<length-percentage>{1,2}",
      "border-end-start-radius": "<length-percentage>{1,2}",
      "border-image": "<'border-image-source'>||<'border-image-slice'> [/ <'border-image-width'>|/ <'border-image-width'>? / <'border-image-outset'>]?||<'border-image-repeat'>",
      "border-image-outset": "[<length>|<number>]{1,4}",
      "border-image-repeat": "[stretch|repeat|round|space]{1,2}",
      "border-image-slice": "<number-percentage>{1,4}&&fill?",
      "border-image-source": "none|<image>",
      "border-image-width": "[<length-percentage>|<number>|auto]{1,4}",
      "border-inline": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-inline-end": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-inline-color": "<'border-top-color'>{1,2}",
      "border-inline-style": "<'border-top-style'>",
      "border-inline-width": "<'border-top-width'>",
      "border-inline-end-color": "<'border-top-color'>",
      "border-inline-end-style": "<'border-top-style'>",
      "border-inline-end-width": "<'border-top-width'>",
      "border-inline-start": "<'border-top-width'>||<'border-top-style'>||<color>",
      "border-inline-start-color": "<'border-top-color'>",
      "border-inline-start-style": "<'border-top-style'>",
      "border-inline-start-width": "<'border-top-width'>",
      "border-left": "<line-width>||<line-style>||<color>",
      "border-left-color": "<color>",
      "border-left-style": "<line-style>",
      "border-left-width": "<line-width>",
      "border-radius": "<length-percentage>{1,4} [/ <length-percentage>{1,4}]?",
      "border-right": "<line-width>||<line-style>||<color>",
      "border-right-color": "<color>",
      "border-right-style": "<line-style>",
      "border-right-width": "<line-width>",
      "border-spacing": "<length> <length>?",
      "border-start-end-radius": "<length-percentage>{1,2}",
      "border-start-start-radius": "<length-percentage>{1,2}",
      "border-style": "<line-style>{1,4}",
      "border-top": "<line-width>||<line-style>||<color>",
      "border-top-color": "<color>",
      "border-top-left-radius": "<length-percentage>{1,2}",
      "border-top-right-radius": "<length-percentage>{1,2}",
      "border-top-style": "<line-style>",
      "border-top-width": "<line-width>",
      "border-width": "<line-width>{1,4}",
      "bottom": "<length>|<percentage>|auto",
      "box-align": "start|center|end|baseline|stretch",
      "box-decoration-break": "slice|clone",
      "box-direction": "normal|reverse|inherit",
      "box-flex": "<number>",
      "box-flex-group": "<integer>",
      "box-lines": "single|multiple",
      "box-ordinal-group": "<integer>",
      "box-orient": "horizontal|vertical|inline-axis|block-axis|inherit",
      "box-pack": "start|center|end|justify",
      "box-shadow": "none|<shadow>#",
      "box-sizing": "content-box|border-box",
      "break-after": "auto|avoid|always|all|avoid-page|page|left|right|recto|verso|avoid-column|column|avoid-region|region",
      "break-before": "auto|avoid|always|all|avoid-page|page|left|right|recto|verso|avoid-column|column|avoid-region|region",
      "break-inside": "auto|avoid|avoid-page|avoid-column|avoid-region",
      "caption-side": "top|bottom|block-start|block-end|inline-start|inline-end",
      "caret-color": "auto|<color>",
      "clear": "none|left|right|both|inline-start|inline-end",
      "clip": "<shape>|auto",
      "clip-path": "<clip-source>|[<basic-shape>||<geometry-box>]|none",
      "color": "<color>",
      "print-color-adjust": "economy|exact",
      "color-scheme": "normal|[light|dark|<custom-ident>]+&&only?",
      "column-count": "<integer>|auto",
      "column-fill": "auto|balance|balance-all",
      "column-gap": "normal|<length-percentage>",
      "column-rule": "<'column-rule-width'>||<'column-rule-style'>||<'column-rule-color'>",
      "column-rule-color": "<color>",
      "column-rule-style": "<'border-style'>",
      "column-rule-width": "<'border-width'>",
      "column-span": "none|all",
      "column-width": "<length>|auto",
      "columns": "<'column-width'>||<'column-count'>",
      "contain": "none|strict|content|[size||layout||style||paint]",
      "content": "normal|none|[<content-replacement>|<content-list>] [/ [<string>|<counter>]+]?",
      "content-visibility": "visible|auto|hidden",
      "counter-increment": "[<counter-name> <integer>?]+|none",
      "counter-reset": "[<counter-name> <integer>?|<reversed-counter-name> <integer>?]+|none",
      "counter-set": "[<counter-name> <integer>?]+|none",
      "cursor": "[[<url> [<x> <y>]? ,]* [auto|default|none|context-menu|help|pointer|progress|wait|cell|crosshair|text|vertical-text|alias|copy|move|no-drop|not-allowed|e-resize|n-resize|ne-resize|nw-resize|s-resize|se-resize|sw-resize|w-resize|ew-resize|ns-resize|nesw-resize|nwse-resize|col-resize|row-resize|all-scroll|zoom-in|zoom-out|grab|grabbing|hand|-webkit-grab|-webkit-grabbing|-webkit-zoom-in|-webkit-zoom-out|-moz-grab|-moz-grabbing|-moz-zoom-in|-moz-zoom-out]]",
      "direction": "ltr|rtl",
      "display": "[<display-outside>||<display-inside>]|<display-listitem>|<display-internal>|<display-box>|<display-legacy>|<-non-standard-display>",
      "empty-cells": "show|hide",
      "filter": "none|<filter-function-list>|<-ms-filter-function-list>",
      "flex": "none|[<'flex-grow'> <'flex-shrink'>?||<'flex-basis'>]",
      "flex-basis": "content|<'width'>",
      "flex-direction": "row|row-reverse|column|column-reverse",
      "flex-flow": "<'flex-direction'>||<'flex-wrap'>",
      "flex-grow": "<number>",
      "flex-shrink": "<number>",
      "flex-wrap": "nowrap|wrap|wrap-reverse",
      "float": "left|right|none|inline-start|inline-end",
      "font": "[[<'font-style'>||<font-variant-css21>||<'font-weight'>||<'font-stretch'>]? <'font-size'> [/ <'line-height'>]? <'font-family'>]|caption|icon|menu|message-box|small-caption|status-bar",
      "font-family": "[<family-name>|<generic-family>]#",
      "font-feature-settings": "normal|<feature-tag-value>#",
      "font-kerning": "auto|normal|none",
      "font-language-override": "normal|<string>",
      "font-optical-sizing": "auto|none",
      "font-variation-settings": "normal|[<string> <number>]#",
      "font-size": "<absolute-size>|<relative-size>|<length-percentage>",
      "font-size-adjust": "none|[ex-height|cap-height|ch-width|ic-width|ic-height]? [from-font|<number>]",
      "font-smooth": "auto|never|always|<absolute-size>|<length>",
      "font-stretch": "<font-stretch-absolute>",
      "font-style": "normal|italic|oblique <angle>?",
      "font-synthesis": "none|[weight||style||small-caps]",
      "font-variant": "normal|none|[<common-lig-values>||<discretionary-lig-values>||<historical-lig-values>||<contextual-alt-values>||stylistic( <feature-value-name> )||historical-forms||styleset( <feature-value-name># )||character-variant( <feature-value-name># )||swash( <feature-value-name> )||ornaments( <feature-value-name> )||annotation( <feature-value-name> )||[small-caps|all-small-caps|petite-caps|all-petite-caps|unicase|titling-caps]||<numeric-figure-values>||<numeric-spacing-values>||<numeric-fraction-values>||ordinal||slashed-zero||<east-asian-variant-values>||<east-asian-width-values>||ruby]",
      "font-variant-alternates": "normal|[stylistic( <feature-value-name> )||historical-forms||styleset( <feature-value-name># )||character-variant( <feature-value-name># )||swash( <feature-value-name> )||ornaments( <feature-value-name> )||annotation( <feature-value-name> )]",
      "font-variant-caps": "normal|small-caps|all-small-caps|petite-caps|all-petite-caps|unicase|titling-caps",
      "font-variant-east-asian": "normal|[<east-asian-variant-values>||<east-asian-width-values>||ruby]",
      "font-variant-ligatures": "normal|none|[<common-lig-values>||<discretionary-lig-values>||<historical-lig-values>||<contextual-alt-values>]",
      "font-variant-numeric": "normal|[<numeric-figure-values>||<numeric-spacing-values>||<numeric-fraction-values>||ordinal||slashed-zero]",
      "font-variant-position": "normal|sub|super",
      "font-weight": "<font-weight-absolute>|bolder|lighter",
      "forced-color-adjust": "auto|none",
      "gap": "<'row-gap'> <'column-gap'>?",
      "grid": "<'grid-template'>|<'grid-template-rows'> / [auto-flow&&dense?] <'grid-auto-columns'>?|[auto-flow&&dense?] <'grid-auto-rows'>? / <'grid-template-columns'>",
      "grid-area": "<grid-line> [/ <grid-line>]{0,3}",
      "grid-auto-columns": "<track-size>+",
      "grid-auto-flow": "[row|column]||dense",
      "grid-auto-rows": "<track-size>+",
      "grid-column": "<grid-line> [/ <grid-line>]?",
      "grid-column-end": "<grid-line>",
      "grid-column-gap": "<length-percentage>",
      "grid-column-start": "<grid-line>",
      "grid-gap": "<'grid-row-gap'> <'grid-column-gap'>?",
      "grid-row": "<grid-line> [/ <grid-line>]?",
      "grid-row-end": "<grid-line>",
      "grid-row-gap": "<length-percentage>",
      "grid-row-start": "<grid-line>",
      "grid-template": "none|[<'grid-template-rows'> / <'grid-template-columns'>]|[<line-names>? <string> <track-size>? <line-names>?]+ [/ <explicit-track-list>]?",
      "grid-template-areas": "none|<string>+",
      "grid-template-columns": "none|<track-list>|<auto-track-list>|subgrid <line-name-list>?",
      "grid-template-rows": "none|<track-list>|<auto-track-list>|subgrid <line-name-list>?",
      "hanging-punctuation": "none|[first||[force-end|allow-end]||last]",
      "height": "auto|<length>|<percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )",
      "hyphenate-character": "auto|<string>",
      "hyphens": "none|manual|auto",
      "image-orientation": "from-image|<angle>|[<angle>? flip]",
      "image-rendering": "auto|crisp-edges|pixelated|optimizeSpeed|optimizeQuality|<-non-standard-image-rendering>",
      "image-resolution": "[from-image||<resolution>]&&snap?",
      "ime-mode": "auto|normal|active|inactive|disabled",
      "initial-letter": "normal|[<number> <integer>?]",
      "initial-letter-align": "[auto|alphabetic|hanging|ideographic]",
      "inline-size": "<'width'>",
      "input-security": "auto|none",
      "inset": "<'top'>{1,4}",
      "inset-block": "<'top'>{1,2}",
      "inset-block-end": "<'top'>",
      "inset-block-start": "<'top'>",
      "inset-inline": "<'top'>{1,2}",
      "inset-inline-end": "<'top'>",
      "inset-inline-start": "<'top'>",
      "isolation": "auto|isolate",
      "justify-content": "normal|<content-distribution>|<overflow-position>? [<content-position>|left|right]",
      "justify-items": "normal|stretch|<baseline-position>|<overflow-position>? [<self-position>|left|right]|legacy|legacy&&[left|right|center]",
      "justify-self": "auto|normal|stretch|<baseline-position>|<overflow-position>? [<self-position>|left|right]",
      "justify-tracks": "[normal|<content-distribution>|<overflow-position>? [<content-position>|left|right]]#",
      "left": "<length>|<percentage>|auto",
      "letter-spacing": "normal|<length-percentage>",
      "line-break": "auto|loose|normal|strict|anywhere",
      "line-clamp": "none|<integer>",
      "line-height": "normal|<number>|<length>|<percentage>",
      "line-height-step": "<length>",
      "list-style": "<'list-style-type'>||<'list-style-position'>||<'list-style-image'>",
      "list-style-image": "<image>|none",
      "list-style-position": "inside|outside",
      "list-style-type": "<counter-style>|<string>|none",
      "margin": "[<length>|<percentage>|auto]{1,4}",
      "margin-block": "<'margin-left'>{1,2}",
      "margin-block-end": "<'margin-left'>",
      "margin-block-start": "<'margin-left'>",
      "margin-bottom": "<length>|<percentage>|auto",
      "margin-inline": "<'margin-left'>{1,2}",
      "margin-inline-end": "<'margin-left'>",
      "margin-inline-start": "<'margin-left'>",
      "margin-left": "<length>|<percentage>|auto",
      "margin-right": "<length>|<percentage>|auto",
      "margin-top": "<length>|<percentage>|auto",
      "margin-trim": "none|in-flow|all",
      "mask": "<mask-layer>#",
      "mask-border": "<'mask-border-source'>||<'mask-border-slice'> [/ <'mask-border-width'>? [/ <'mask-border-outset'>]?]?||<'mask-border-repeat'>||<'mask-border-mode'>",
      "mask-border-mode": "luminance|alpha",
      "mask-border-outset": "[<length>|<number>]{1,4}",
      "mask-border-repeat": "[stretch|repeat|round|space]{1,2}",
      "mask-border-slice": "<number-percentage>{1,4} fill?",
      "mask-border-source": "none|<image>",
      "mask-border-width": "[<length-percentage>|<number>|auto]{1,4}",
      "mask-clip": "[<geometry-box>|no-clip]#",
      "mask-composite": "<compositing-operator>#",
      "mask-image": "<mask-reference>#",
      "mask-mode": "<masking-mode>#",
      "mask-origin": "<geometry-box>#",
      "mask-position": "<position>#",
      "mask-repeat": "<repeat-style>#",
      "mask-size": "<bg-size>#",
      "mask-type": "luminance|alpha",
      "masonry-auto-flow": "[pack|next]||[definite-first|ordered]",
      "math-style": "normal|compact",
      "max-block-size": "<'max-width'>",
      "max-height": "none|<length-percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )",
      "max-inline-size": "<'max-width'>",
      "max-lines": "none|<integer>",
      "max-width": "none|<length-percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )|<-non-standard-width>",
      "min-block-size": "<'min-width'>",
      "min-height": "auto|<length>|<percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )",
      "min-inline-size": "<'min-width'>",
      "min-width": "auto|<length>|<percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )|<-non-standard-width>",
      "mix-blend-mode": "<blend-mode>|plus-lighter",
      "object-fit": "fill|contain|cover|none|scale-down",
      "object-position": "<position>",
      "offset": "[<'offset-position'>? [<'offset-path'> [<'offset-distance'>||<'offset-rotate'>]?]?]! [/ <'offset-anchor'>]?",
      "offset-anchor": "auto|<position>",
      "offset-distance": "<length-percentage>",
      "offset-path": "none|ray( [<angle>&&<size>&&contain?] )|<path()>|<url>|[<basic-shape>||<geometry-box>]",
      "offset-position": "auto|<position>",
      "offset-rotate": "[auto|reverse]||<angle>",
      "opacity": "<alpha-value>",
      "order": "<integer>",
      "orphans": "<integer>",
      "outline": "[<'outline-color'>||<'outline-style'>||<'outline-width'>]",
      "outline-color": "<color>|invert",
      "outline-offset": "<length>",
      "outline-style": "auto|<'border-style'>",
      "outline-width": "<line-width>",
      "overflow": "[visible|hidden|clip|scroll|auto]{1,2}|<-non-standard-overflow>",
      "overflow-anchor": "auto|none",
      "overflow-block": "visible|hidden|clip|scroll|auto",
      "overflow-clip-box": "padding-box|content-box",
      "overflow-clip-margin": "<visual-box>||<length [0,∞]>",
      "overflow-inline": "visible|hidden|clip|scroll|auto",
      "overflow-wrap": "normal|break-word|anywhere",
      "overflow-x": "visible|hidden|clip|scroll|auto",
      "overflow-y": "visible|hidden|clip|scroll|auto",
      "overscroll-behavior": "[contain|none|auto]{1,2}",
      "overscroll-behavior-block": "contain|none|auto",
      "overscroll-behavior-inline": "contain|none|auto",
      "overscroll-behavior-x": "contain|none|auto",
      "overscroll-behavior-y": "contain|none|auto",
      "padding": "[<length>|<percentage>]{1,4}",
      "padding-block": "<'padding-left'>{1,2}",
      "padding-block-end": "<'padding-left'>",
      "padding-block-start": "<'padding-left'>",
      "padding-bottom": "<length>|<percentage>",
      "padding-inline": "<'padding-left'>{1,2}",
      "padding-inline-end": "<'padding-left'>",
      "padding-inline-start": "<'padding-left'>",
      "padding-left": "<length>|<percentage>",
      "padding-right": "<length>|<percentage>",
      "padding-top": "<length>|<percentage>",
      "page-break-after": "auto|always|avoid|left|right|recto|verso",
      "page-break-before": "auto|always|avoid|left|right|recto|verso",
      "page-break-inside": "auto|avoid",
      "paint-order": "normal|[fill||stroke||markers]",
      "perspective": "none|<length>",
      "perspective-origin": "<position>",
      "place-content": "<'align-content'> <'justify-content'>?",
      "place-items": "<'align-items'> <'justify-items'>?",
      "place-self": "<'align-self'> <'justify-self'>?",
      "pointer-events": "auto|none|visiblePainted|visibleFill|visibleStroke|visible|painted|fill|stroke|all|inherit",
      "position": "static|relative|absolute|sticky|fixed|-webkit-sticky",
      "quotes": "none|auto|[<string> <string>]+",
      "resize": "none|both|horizontal|vertical|block|inline",
      "right": "<length>|<percentage>|auto",
      "rotate": "none|<angle>|[x|y|z|<number>{3}]&&<angle>",
      "row-gap": "normal|<length-percentage>",
      "ruby-align": "start|center|space-between|space-around",
      "ruby-merge": "separate|collapse|auto",
      "ruby-position": "[alternate||[over|under]]|inter-character",
      "scale": "none|<number>{1,3}",
      "scrollbar-color": "auto|<color>{2}",
      "scrollbar-gutter": "auto|stable&&both-edges?",
      "scrollbar-width": "auto|thin|none",
      "scroll-behavior": "auto|smooth",
      "scroll-margin": "<length>{1,4}",
      "scroll-margin-block": "<length>{1,2}",
      "scroll-margin-block-start": "<length>",
      "scroll-margin-block-end": "<length>",
      "scroll-margin-bottom": "<length>",
      "scroll-margin-inline": "<length>{1,2}",
      "scroll-margin-inline-start": "<length>",
      "scroll-margin-inline-end": "<length>",
      "scroll-margin-left": "<length>",
      "scroll-margin-right": "<length>",
      "scroll-margin-top": "<length>",
      "scroll-padding": "[auto|<length-percentage>]{1,4}",
      "scroll-padding-block": "[auto|<length-percentage>]{1,2}",
      "scroll-padding-block-start": "auto|<length-percentage>",
      "scroll-padding-block-end": "auto|<length-percentage>",
      "scroll-padding-bottom": "auto|<length-percentage>",
      "scroll-padding-inline": "[auto|<length-percentage>]{1,2}",
      "scroll-padding-inline-start": "auto|<length-percentage>",
      "scroll-padding-inline-end": "auto|<length-percentage>",
      "scroll-padding-left": "auto|<length-percentage>",
      "scroll-padding-right": "auto|<length-percentage>",
      "scroll-padding-top": "auto|<length-percentage>",
      "scroll-snap-align": "[none|start|end|center]{1,2}",
      "scroll-snap-coordinate": "none|<position>#",
      "scroll-snap-destination": "<position>",
      "scroll-snap-points-x": "none|repeat( <length-percentage> )",
      "scroll-snap-points-y": "none|repeat( <length-percentage> )",
      "scroll-snap-stop": "normal|always",
      "scroll-snap-type": "none|[x|y|block|inline|both] [mandatory|proximity]?",
      "scroll-snap-type-x": "none|mandatory|proximity",
      "scroll-snap-type-y": "none|mandatory|proximity",
      "shape-image-threshold": "<alpha-value>",
      "shape-margin": "<length-percentage>",
      "shape-outside": "none|[<shape-box>||<basic-shape>]|<image>",
      "tab-size": "<integer>|<length>",
      "table-layout": "auto|fixed",
      "text-align": "start|end|left|right|center|justify|match-parent",
      "text-align-last": "auto|start|end|left|right|center|justify",
      "text-combine-upright": "none|all|[digits <integer>?]",
      "text-decoration": "<'text-decoration-line'>||<'text-decoration-style'>||<'text-decoration-color'>||<'text-decoration-thickness'>",
      "text-decoration-color": "<color>",
      "text-decoration-line": "none|[underline||overline||line-through||blink]|spelling-error|grammar-error",
      "text-decoration-skip": "none|[objects||[spaces|[leading-spaces||trailing-spaces]]||edges||box-decoration]",
      "text-decoration-skip-ink": "auto|all|none",
      "text-decoration-style": "solid|double|dotted|dashed|wavy",
      "text-decoration-thickness": "auto|from-font|<length>|<percentage>",
      "text-emphasis": "<'text-emphasis-style'>||<'text-emphasis-color'>",
      "text-emphasis-color": "<color>",
      "text-emphasis-position": "[over|under]&&[right|left]",
      "text-emphasis-style": "none|[[filled|open]||[dot|circle|double-circle|triangle|sesame]]|<string>",
      "text-indent": "<length-percentage>&&hanging?&&each-line?",
      "text-justify": "auto|inter-character|inter-word|none",
      "text-orientation": "mixed|upright|sideways",
      "text-overflow": "[clip|ellipsis|<string>]{1,2}",
      "text-rendering": "auto|optimizeSpeed|optimizeLegibility|geometricPrecision",
      "text-shadow": "none|<shadow-t>#",
      "text-size-adjust": "none|auto|<percentage>",
      "text-transform": "none|capitalize|uppercase|lowercase|full-width|full-size-kana",
      "text-underline-offset": "auto|<length>|<percentage>",
      "text-underline-position": "auto|from-font|[under||[left|right]]",
      "top": "<length>|<percentage>|auto",
      "touch-action": "auto|none|[[pan-x|pan-left|pan-right]||[pan-y|pan-up|pan-down]||pinch-zoom]|manipulation",
      "transform": "none|<transform-list>",
      "transform-box": "content-box|border-box|fill-box|stroke-box|view-box",
      "transform-origin": "[<length-percentage>|left|center|right|top|bottom]|[[<length-percentage>|left|center|right]&&[<length-percentage>|top|center|bottom]] <length>?",
      "transform-style": "flat|preserve-3d",
      "transition": "<single-transition>#",
      "transition-delay": "<time>#",
      "transition-duration": "<time>#",
      "transition-property": "none|<single-transition-property>#",
      "transition-timing-function": "<easing-function>#",
      "translate": "none|<length-percentage> [<length-percentage> <length>?]?",
      "unicode-bidi": "normal|embed|isolate|bidi-override|isolate-override|plaintext|-moz-isolate|-moz-isolate-override|-moz-plaintext|-webkit-isolate|-webkit-isolate-override|-webkit-plaintext",
      "user-select": "auto|text|none|contain|all",
      "vertical-align": "baseline|sub|super|text-top|text-bottom|middle|top|bottom|<percentage>|<length>",
      "visibility": "visible|hidden|collapse",
      "white-space": "normal|pre|nowrap|pre-wrap|pre-line|break-spaces",
      "widows": "<integer>",
      "width": "auto|<length>|<percentage>|min-content|max-content|fit-content|fit-content( <length-percentage> )|fill|stretch|intrinsic|-moz-max-content|-webkit-max-content|-moz-fit-content|-webkit-fit-content",
      "will-change": "auto|<animateable-feature>#",
      "word-break": "normal|break-all|keep-all|break-word",
      "word-spacing": "normal|<length>",
      "word-wrap": "normal|break-word",
      "writing-mode": "horizontal-tb|vertical-rl|vertical-lr|sideways-rl|sideways-lr|<svg-writing-mode>",
      "z-index": "auto|<integer>",
      "zoom": "normal|reset|<number>|<percentage>",
      "-moz-background-clip": "padding|border",
      "-moz-border-radius-bottomleft": "<'border-bottom-left-radius'>",
      "-moz-border-radius-bottomright": "<'border-bottom-right-radius'>",
      "-moz-border-radius-topleft": "<'border-top-left-radius'>",
      "-moz-border-radius-topright": "<'border-bottom-right-radius'>",
      "-moz-control-character-visibility": "visible|hidden",
      "-moz-osx-font-smoothing": "auto|grayscale",
      "-moz-user-select": "none|text|all|-moz-none",
      "-ms-flex-align": "start|end|center|baseline|stretch",
      "-ms-flex-item-align": "auto|start|end|center|baseline|stretch",
      "-ms-flex-line-pack": "start|end|center|justify|distribute|stretch",
      "-ms-flex-negative": "<'flex-shrink'>",
      "-ms-flex-pack": "start|end|center|justify|distribute",
      "-ms-flex-order": "<integer>",
      "-ms-flex-positive": "<'flex-grow'>",
      "-ms-flex-preferred-size": "<'flex-basis'>",
      "-ms-interpolation-mode": "nearest-neighbor|bicubic",
      "-ms-grid-column-align": "start|end|center|stretch",
      "-ms-grid-row-align": "start|end|center|stretch",
      "-ms-hyphenate-limit-last": "none|always|column|page|spread",
      "-webkit-background-clip": "[<box>|border|padding|content|text]#",
      "-webkit-column-break-after": "always|auto|avoid",
      "-webkit-column-break-before": "always|auto|avoid",
      "-webkit-column-break-inside": "always|auto|avoid",
      "-webkit-font-smoothing": "auto|none|antialiased|subpixel-antialiased",
      "-webkit-mask-box-image": "[<url>|<gradient>|none] [<length-percentage>{4} <-webkit-mask-box-repeat>{2}]?",
      "-webkit-print-color-adjust": "economy|exact",
      "-webkit-text-security": "none|circle|disc|square",
      "-webkit-user-drag": "none|element|auto",
      "-webkit-user-select": "auto|none|text|all",
      "alignment-baseline": "auto|baseline|before-edge|text-before-edge|middle|central|after-edge|text-after-edge|ideographic|alphabetic|hanging|mathematical",
      "baseline-shift": "baseline|sub|super|<svg-length>",
      "behavior": "<url>+",
      "clip-rule": "nonzero|evenodd",
      "cue": "<'cue-before'> <'cue-after'>?",
      "cue-after": "<url> <decibel>?|none",
      "cue-before": "<url> <decibel>?|none",
      "dominant-baseline": "auto|use-script|no-change|reset-size|ideographic|alphabetic|hanging|mathematical|central|middle|text-after-edge|text-before-edge",
      "fill": "<paint>",
      "fill-opacity": "<number-zero-one>",
      "fill-rule": "nonzero|evenodd",
      "glyph-orientation-horizontal": "<angle>",
      "glyph-orientation-vertical": "<angle>",
      "kerning": "auto|<svg-length>",
      "marker": "none|<url>",
      "marker-end": "none|<url>",
      "marker-mid": "none|<url>",
      "marker-start": "none|<url>",
      "pause": "<'pause-before'> <'pause-after'>?",
      "pause-after": "<time>|none|x-weak|weak|medium|strong|x-strong",
      "pause-before": "<time>|none|x-weak|weak|medium|strong|x-strong",
      "rest": "<'rest-before'> <'rest-after'>?",
      "rest-after": "<time>|none|x-weak|weak|medium|strong|x-strong",
      "rest-before": "<time>|none|x-weak|weak|medium|strong|x-strong",
      "shape-rendering": "auto|optimizeSpeed|crispEdges|geometricPrecision",
      "src": "[<url> [format( <string># )]?|local( <family-name> )]#",
      "speak": "auto|none|normal",
      "speak-as": "normal|spell-out||digits||[literal-punctuation|no-punctuation]",
      "stroke": "<paint>",
      "stroke-dasharray": "none|[<svg-length>+]#",
      "stroke-dashoffset": "<svg-length>",
      "stroke-linecap": "butt|round|square",
      "stroke-linejoin": "miter|round|bevel",
      "stroke-miterlimit": "<number-one-or-greater>",
      "stroke-opacity": "<number-zero-one>",
      "stroke-width": "<svg-length>",
      "text-anchor": "start|middle|end",
      "unicode-range": "<urange>#",
      "voice-balance": "<number>|left|center|right|leftwards|rightwards",
      "voice-duration": "auto|<time>",
      "voice-family": "[[<family-name>|<generic-voice>] ,]* [<family-name>|<generic-voice>]|preserve",
      "voice-pitch": "<frequency>&&absolute|[[x-low|low|medium|high|x-high]||[<frequency>|<semitones>|<percentage>]]",
      "voice-range": "<frequency>&&absolute|[[x-low|low|medium|high|x-high]||[<frequency>|<semitones>|<percentage>]]",
      "voice-rate": "[normal|x-slow|slow|medium|fast|x-fast]||<percentage>",
      "voice-stress": "normal|strong|moderate|none|reduced",
      "voice-volume": "silent|[[x-soft|soft|medium|loud|x-loud]||<decibel>]"
    },
    "atrules": {
      "charset": {
        "prelude": "<string>",
        "descriptors": null
      },
      "counter-style": {
        "prelude": "<counter-style-name>",
        "descriptors": {
          "additive-symbols": "[<integer>&&<symbol>]#",
          "fallback": "<counter-style-name>",
          "negative": "<symbol> <symbol>?",
          "pad": "<integer>&&<symbol>",
          "prefix": "<symbol>",
          "range": "[[<integer>|infinite]{2}]#|auto",
          "speak-as": "auto|bullets|numbers|words|spell-out|<counter-style-name>",
          "suffix": "<symbol>",
          "symbols": "<symbol>+",
          "system": "cyclic|numeric|alphabetic|symbolic|additive|[fixed <integer>?]|[extends <counter-style-name>]"
        }
      },
      "document": {
        "prelude": "[<url>|url-prefix( <string> )|domain( <string> )|media-document( <string> )|regexp( <string> )]#",
        "descriptors": null
      },
      "font-face": {
        "prelude": null,
        "descriptors": {
          "ascent-override": "normal|<percentage>",
          "descent-override": "normal|<percentage>",
          "font-display": "[auto|block|swap|fallback|optional]",
          "font-family": "<family-name>",
          "font-feature-settings": "normal|<feature-tag-value>#",
          "font-variation-settings": "normal|[<string> <number>]#",
          "font-stretch": "<font-stretch-absolute>{1,2}",
          "font-style": "normal|italic|oblique <angle>{0,2}",
          "font-weight": "<font-weight-absolute>{1,2}",
          "font-variant": "normal|none|[<common-lig-values>||<discretionary-lig-values>||<historical-lig-values>||<contextual-alt-values>||stylistic( <feature-value-name> )||historical-forms||styleset( <feature-value-name># )||character-variant( <feature-value-name># )||swash( <feature-value-name> )||ornaments( <feature-value-name> )||annotation( <feature-value-name> )||[small-caps|all-small-caps|petite-caps|all-petite-caps|unicase|titling-caps]||<numeric-figure-values>||<numeric-spacing-values>||<numeric-fraction-values>||ordinal||slashed-zero||<east-asian-variant-values>||<east-asian-width-values>||ruby]",
          "line-gap-override": "normal|<percentage>",
          "size-adjust": "<percentage>",
          "src": "[<url> [format( <string># )]?|local( <family-name> )]#",
          "unicode-range": "<urange>#"
        }
      },
      "font-feature-values": {
        "prelude": "<family-name>#",
        "descriptors": null
      },
      "import": {
        "prelude": "[<string>|<url>] [layer|layer( <layer-name> )]? [supports( [<supports-condition>|<declaration>] )]? <media-query-list>?",
        "descriptors": null
      },
      "keyframes": {
        "prelude": "<keyframes-name>",
        "descriptors": null
      },
      "layer": {
        "prelude": "[<layer-name>#|<layer-name>?]",
        "descriptors": null
      },
      "media": {
        "prelude": "<media-query-list>",
        "descriptors": null
      },
      "namespace": {
        "prelude": "<namespace-prefix>? [<string>|<url>]",
        "descriptors": null
      },
      "page": {
        "prelude": "<page-selector-list>",
        "descriptors": {
          "bleed": "auto|<length>",
          "marks": "none|[crop||cross]",
          "size": "<length>{1,2}|auto|[<page-size>||[portrait|landscape]]"
        }
      },
      "property": {
        "prelude": "<custom-property-name>",
        "descriptors": {
          "syntax": "<string>",
          "inherits": "true|false",
          "initial-value": "<string>"
        }
      },
      "scroll-timeline": {
        "prelude": "<timeline-name>",
        "descriptors": null
      },
      "supports": {
        "prelude": "<supports-condition>",
        "descriptors": null
      },
      "viewport": {
        "prelude": null,
        "descriptors": {
          "height": "<viewport-length>{1,2}",
          "max-height": "<viewport-length>",
          "max-width": "<viewport-length>",
          "max-zoom": "auto|<number>|<percentage>",
          "min-height": "<viewport-length>",
          "min-width": "<viewport-length>",
          "min-zoom": "auto|<number>|<percentage>",
          "orientation": "auto|portrait|landscape",
          "user-zoom": "zoom|fixed",
          "viewport-fit": "auto|contain|cover",
          "width": "<viewport-length>{1,2}",
          "zoom": "auto|<number>|<percentage>"
        }
      }
    }
  },
  node
}, scope = {};

const types$5 = types$R;

var _default$3 = function(context) {
  switch (this.tokenType) {
   case types$5.Hash:
    return this.Hash();

   case types$5.Comma:
    return this.Operator();

   case types$5.LeftParenthesis:
    return this.Parentheses(this.readSequence, context.recognizer);

   case types$5.LeftSquareBracket:
    return this.Brackets(this.readSequence, context.recognizer);

   case types$5.String:
    return this.String();

   case types$5.Dimension:
    return this.Dimension();

   case types$5.Percentage:
    return this.Percentage();

   case types$5.Number:
    return this.Number();

   case types$5.Function:
    return this.cmpStr(this.tokenStart, this.tokenEnd, "url(") ? this.Url() : this.Function(this.readSequence, context.recognizer);

   case types$5.Url:
    return this.Url();

   case types$5.Ident:
    return this.cmpChar(this.tokenStart, 117) && this.cmpChar(this.tokenStart + 1, 43) ? this.UnicodeRange() : this.Identifier();

   case types$5.Delim:
    {
      const code = this.charCodeAt(this.tokenStart);
      if (47 === code || 42 === code || 43 === code || 45 === code) return this.Operator();
      35 === code && this.error("Hex or identifier is expected", this.tokenStart + 1);
      break;
    }
  }
};

const types$4 = types$R;

const types$3 = types$R;

var _var$1 = function() {
  const children = this.createList();
  if (this.skipSC(), children.push(this.Identifier()), this.skipSC(), this.tokenType === types$3.Comma) {
    children.push(this.Operator());
    const startIndex = this.tokenIndex, value = this.parseCustomProperty ? this.Value(null) : this.Raw(this.tokenIndex, this.consumeUntilExclamationMarkOrSemicolon, !1);
    if ("Value" === value.type && value.children.isEmpty) for (let offset = startIndex - this.tokenIndex; offset <= 0; offset++) if (this.lookupType(offset) === types$3.WhiteSpace) {
      value.children.appendData({
        type: "WhiteSpace",
        loc: null,
        value: " "
      });
      break;
    }
    children.push(value);
  }
  return children;
};

function isPlusMinusOperator(node) {
  return null !== node && "Operator" === node.type && ("-" === node.value[node.value.length - 1] || "+" === node.value[node.value.length - 1]);
}

const atrulePrelude = {
  getNode: _default$3
}, selector$1 = {
  onWhiteSpace: function(next, children) {
    null !== children.last && "Combinator" !== children.last.type && null !== next && "Combinator" !== next.type && children.push({
      type: "Combinator",
      loc: null,
      name: " "
    });
  },
  getNode: function() {
    switch (this.tokenType) {
     case types$4.LeftSquareBracket:
      return this.AttributeSelector();

     case types$4.Hash:
      return this.IdSelector();

     case types$4.Colon:
      return this.lookupType(1) === types$4.Colon ? this.PseudoElementSelector() : this.PseudoClassSelector();

     case types$4.Ident:
      return this.TypeSelector();

     case types$4.Number:
     case types$4.Percentage:
      return this.Percentage();

     case types$4.Dimension:
      46 === this.charCodeAt(this.tokenStart) && this.error("Identifier is expected", this.tokenStart + 1);
      break;

     case types$4.Delim:
      switch (this.charCodeAt(this.tokenStart)) {
       case 43:
       case 62:
       case 126:
       case 47:
        return this.Combinator();

       case 46:
        return this.ClassSelector();

       case 42:
       case 124:
        return this.TypeSelector();

       case 35:
        return this.IdSelector();
      }
      break;
    }
  }
}, value = {
  getNode: _default$3,
  onWhiteSpace(next, children) {
    isPlusMinusOperator(next) && (next.value = " " + next.value), isPlusMinusOperator(children.last) && (children.last.value += " ");
  },
  "expression": function() {
    return this.createSingleNodeList(this.Raw(this.tokenIndex, null, !1));
  },
  "var": _var$1
};

scope.AtrulePrelude = atrulePrelude, scope.Selector = selector$1, scope.Value = value;

const types$2 = types$R;

const types$1 = types$R;

function consumeRaw() {
  return this.createSingleNodeList(this.Raw(this.tokenIndex, null, !1));
}

function parentheses() {
  return this.skipSC(), this.tokenType === types$1.Ident && this.lookupNonWSType(1) === types$1.Colon ? this.createSingleNodeList(this.Declaration()) : readSequence.call(this);
}

function readSequence() {
  const children = this.createList();
  let child;
  this.skipSC();
  scan: for (;!this.eof; ) {
    switch (this.tokenType) {
     case types$1.Comment:
     case types$1.WhiteSpace:
      this.next();
      continue;

     case types$1.Function:
      child = this.Function(consumeRaw, this.scope.AtrulePrelude);
      break;

     case types$1.Ident:
      child = this.Identifier();
      break;

     case types$1.LeftParenthesis:
      child = this.Parentheses(parentheses, this.scope.AtrulePrelude);
      break;

     default:
      break scan;
    }
    children.push(child);
  }
  return children;
}

var atrule_1 = {
  "font-face": {
    parse: {
      prelude: null,
      block() {
        return this.Block(!0);
      }
    }
  },
  "import": {
    parse: {
      prelude() {
        const children = this.createList();
        switch (this.skipSC(), this.tokenType) {
         case types$2.String:
          children.push(this.String());
          break;

         case types$2.Url:
         case types$2.Function:
          children.push(this.Url());
          break;

         default:
          this.error("String or url() is expected");
        }
        return this.lookupNonWSType(0) !== types$2.Ident && this.lookupNonWSType(0) !== types$2.LeftParenthesis || children.push(this.MediaQueryList()), 
        children;
      },
      block: null
    }
  },
  media: {
    parse: {
      prelude() {
        return this.createSingleNodeList(this.MediaQueryList());
      },
      block() {
        return this.Block(!1);
      }
    }
  },
  page: {
    parse: {
      prelude() {
        return this.createSingleNodeList(this.SelectorList());
      },
      block() {
        return this.Block(!0);
      }
    }
  },
  supports: {
    parse: {
      prelude() {
        const children = readSequence.call(this);
        return null === this.getFirstListNode(children) && this.error("Condition is expected"), 
        children;
      },
      block() {
        return this.Block(!1);
      }
    }
  }
};

const selectorList = {
  parse() {
    return this.createSingleNodeList(this.SelectorList());
  }
}, identList = {
  parse() {
    return this.createSingleNodeList(this.Identifier());
  }
}, nth = {
  parse() {
    return this.createSingleNodeList(this.Nth());
  }
};

var pseudo_1 = {
  "dir": identList,
  "has": selectorList,
  "lang": identList,
  "matches": selectorList,
  "is": selectorList,
  "-moz-any": selectorList,
  "-webkit-any": selectorList,
  "where": selectorList,
  "not": selectorList,
  "nth-child": nth,
  "nth-last-child": nth,
  "nth-last-of-type": nth,
  "nth-of-type": nth,
  "slotted": {
    parse() {
      return this.createSingleNodeList(this.Selector());
    }
  }
}, indexParse$1 = {};

const AnPlusB = AnPlusB$2, Atrule$4 = Atrule$6, AtrulePrelude = AtrulePrelude$2, AttributeSelector$2 = AttributeSelector$4, Block = Block$2, Brackets = Brackets$2, CDC = CDC$2, CDO = CDO$2, ClassSelector = ClassSelector$2, Combinator = Combinator$2, Comment$2 = Comment$4, Declaration$2 = Declaration$4, DeclarationList = DeclarationList$2, Dimension$2 = Dimension$4, Function$1 = _Function, Hash = Hash$2, Identifier = Identifier$2, IdSelector = IdSelector$2, MediaFeature = MediaFeature$2, MediaQuery = MediaQuery$2, MediaQueryList = MediaQueryList$2, Nth = Nth$2, Number$2 = _Number$5, Operator = Operator$2, Parentheses = Parentheses$2, Percentage$2 = Percentage$4, PseudoClassSelector = PseudoClassSelector$2, PseudoElementSelector = PseudoElementSelector$2, Ratio = Ratio$2, Raw$2 = Raw$4, Rule$2 = Rule$4, Selector = Selector$3, SelectorList = SelectorList$2, String$1 = _String, StyleSheet = StyleSheet$2, TypeSelector$2 = TypeSelector$4, UnicodeRange = UnicodeRange$2, Url$2 = Url$4, Value$2 = Value$4, WhiteSpace$2 = WhiteSpace$4;

indexParse$1.AnPlusB = AnPlusB.parse, indexParse$1.Atrule = Atrule$4.parse, indexParse$1.AtrulePrelude = AtrulePrelude.parse, 
indexParse$1.AttributeSelector = AttributeSelector$2.parse, indexParse$1.Block = Block.parse, 
indexParse$1.Brackets = Brackets.parse, indexParse$1.CDC = CDC.parse, indexParse$1.CDO = CDO.parse, 
indexParse$1.ClassSelector = ClassSelector.parse, indexParse$1.Combinator = Combinator.parse, 
indexParse$1.Comment = Comment$2.parse, indexParse$1.Declaration = Declaration$2.parse, 
indexParse$1.DeclarationList = DeclarationList.parse, indexParse$1.Dimension = Dimension$2.parse, 
indexParse$1.Function = Function$1.parse, indexParse$1.Hash = Hash.parse, indexParse$1.Identifier = Identifier.parse, 
indexParse$1.IdSelector = IdSelector.parse, indexParse$1.MediaFeature = MediaFeature.parse, 
indexParse$1.MediaQuery = MediaQuery.parse, indexParse$1.MediaQueryList = MediaQueryList.parse, 
indexParse$1.Nth = Nth.parse, indexParse$1.Number = Number$2.parse, indexParse$1.Operator = Operator.parse, 
indexParse$1.Parentheses = Parentheses.parse, indexParse$1.Percentage = Percentage$2.parse, 
indexParse$1.PseudoClassSelector = PseudoClassSelector.parse, indexParse$1.PseudoElementSelector = PseudoElementSelector.parse, 
indexParse$1.Ratio = Ratio.parse, indexParse$1.Raw = Raw$2.parse, indexParse$1.Rule = Rule$2.parse, 
indexParse$1.Selector = Selector.parse, indexParse$1.SelectorList = SelectorList.parse, 
indexParse$1.String = String$1.parse, indexParse$1.StyleSheet = StyleSheet.parse, 
indexParse$1.TypeSelector = TypeSelector$2.parse, indexParse$1.UnicodeRange = UnicodeRange.parse, 
indexParse$1.Url = Url$2.parse, indexParse$1.Value = Value$2.parse, indexParse$1.WhiteSpace = WhiteSpace$2.parse;

var syntax_1 = create_1({
  ...lexer$3,
  ...{
    parseContext: {
      default: "StyleSheet",
      stylesheet: "StyleSheet",
      atrule: "Atrule",
      atrulePrelude(options) {
        return this.AtrulePrelude(options.atrule ? String(options.atrule) : null);
      },
      mediaQueryList: "MediaQueryList",
      mediaQuery: "MediaQuery",
      rule: "Rule",
      selectorList: "SelectorList",
      selector: "Selector",
      block() {
        return this.Block(!0);
      },
      declarationList: "DeclarationList",
      declaration: "Declaration",
      value: "Value"
    },
    scope,
    atrule: atrule_1,
    pseudo: pseudo_1,
    node: indexParse$1
  },
  ...{
    node
  }
}), definitionSyntax = {};

const SyntaxError$1 = _SyntaxError, generate$3 = generate$L, parse$3 = parse$L, walk$2 = walk$5;

definitionSyntax.SyntaxError = SyntaxError$1.SyntaxError, definitionSyntax.generate = generate$3.generate, 
definitionSyntax.parse = parse$3.parse, definitionSyntax.walk = walk$2.walk;

var clone$2 = {};

const List$1 = List$7;

clone$2.clone = function clone$1(node) {
  const result = {};
  for (const key in node) {
    let value = node[key];
    value && (Array.isArray(value) || value instanceof List$1.List ? value = value.map(clone$1) : value.constructor === Object && (value = clone$1(value))), 
    result[key] = value;
  }
  return result;
};

var ident$1 = {};

const charCodeDefinitions = charCodeDefinitions$c, utils$b = utils$k;

ident$1.decode = function(str) {
  const end = str.length - 1;
  let decoded = "";
  for (let i = 0; i < str.length; i++) {
    let code = str.charCodeAt(i);
    if (92 === code) {
      if (i === end) break;
      if (code = str.charCodeAt(++i), charCodeDefinitions.isValidEscape(92, code)) {
        const escapeStart = i - 1, escapeEnd = utils$b.consumeEscaped(str, escapeStart);
        i = escapeEnd - 1, decoded += utils$b.decodeEscaped(str.substring(escapeStart + 1, escapeEnd));
      } else 0x000d === code && 0x000a === str.charCodeAt(i + 1) && i++;
    } else decoded += str[i];
  }
  return decoded;
}, ident$1.encode = function(str) {
  let encoded = "";
  if (1 === str.length && 0x002D === str.charCodeAt(0)) return "\\-";
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    0x0000 !== code ? code <= 0x001F || 0x007F === code || code >= 0x0030 && code <= 0x0039 && (0 === i || 1 === i && 0x002D === str.charCodeAt(0)) ? encoded += "\\" + code.toString(16) + " " : charCodeDefinitions.isName(code) ? encoded += str.charAt(i) : encoded += "\\" + str.charAt(i) : encoded += "�";
  }
  return encoded;
};

const index$1$1 = syntax_1, create = create_1, List = List$7, Lexer = Lexer$3, index$4 = definitionSyntax, clone = clone$2, names$1 = names$4, ident = ident$1, string = string$3, url = url$2, types = types$R, names = names$8, TokenStream = TokenStream$4, {tokenize: tokenize$1, parse: parse$2, generate: generate$2, lexer: lexer$1, createLexer, walk: walk$1, find: find$1, findLast: findLast$1, findAll: findAll$1, toPlainObject: toPlainObject$1, fromPlainObject: fromPlainObject$1, fork} = index$1$1;

cjs.version = "2.2.1".version, cjs.createSyntax = create, cjs.List = List.List, 
cjs.Lexer = Lexer.Lexer, cjs.definitionSyntax = index$4, cjs.clone = clone.clone, 
cjs.isCustomProperty = names$1.isCustomProperty, cjs.keyword = names$1.keyword, 
cjs.property = names$1.property, cjs.vendorPrefix = names$1.vendorPrefix, cjs.ident = ident, 
cjs.string = string, cjs.url = url, cjs.tokenTypes = types, cjs.tokenNames = names, 
cjs.TokenStream = TokenStream.TokenStream, cjs.createLexer = createLexer, cjs.find = find$1, 
cjs.findAll = findAll$1, cjs.findLast = findLast$1, cjs.fork = fork, cjs.fromPlainObject = fromPlainObject$1, 
cjs.generate = generate$2, cjs.lexer = lexer$1, cjs.parse = parse$2, cjs.toPlainObject = toPlainObject$1, 
cjs.tokenize = tokenize$1, cjs.walk = walk$1;

var usage$1 = {};

const {hasOwnProperty: hasOwnProperty$6} = Object.prototype;

function buildMap(list, caseInsensitive) {
  const map = Object.create(null);
  if (!Array.isArray(list)) return null;
  for (let name of list) caseInsensitive && (name = name.toLowerCase()), map[name] = !0;
  return map;
}

function buildList(data) {
  if (!data) return null;
  const tags = buildMap(data.tags, !0), ids = buildMap(data.ids), classes = buildMap(data.classes);
  return null === tags && null === ids && null === classes ? null : {
    tags,
    ids,
    classes
  };
}

usage$1.buildIndex = function(data) {
  let scopes = !1;
  if (data.scopes && Array.isArray(data.scopes)) {
    scopes = Object.create(null);
    for (let i = 0; i < data.scopes.length; i++) {
      const list = data.scopes[i];
      if (!list || !Array.isArray(list)) throw new Error("Wrong usage format");
      for (const name of list) {
        if (hasOwnProperty$6.call(scopes, name)) throw new Error(`Class can't be used for several scopes: ${name}`);
        scopes[name] = i + 1;
      }
    }
  }
  return {
    whitelist: buildList(data),
    blacklist: buildList(data.blacklist),
    scopes
  };
};

var utils$a = {};

utils$a.hasNoChildren = function(node) {
  return !node || !node.children || node.children.isEmpty;
}, utils$a.isNodeChildrenList = function(node, list) {
  return null !== node && node.children === list;
};

const cssTree$m = cjs, utils$9 = utils$a;

var Atrule$3 = function(node, item, list) {
  if (node.block && (null !== this.stylesheet && (this.stylesheet.firstAtrulesAllowed = !1), 
  utils$9.hasNoChildren(node.block))) list.remove(item); else switch (node.name) {
   case "charset":
    if (utils$9.hasNoChildren(node.prelude)) return void list.remove(item);
    if (item.prev) return void list.remove(item);
    break;

   case "import":
    if (null === this.stylesheet || !this.stylesheet.firstAtrulesAllowed) return void list.remove(item);
    list.prevUntil(item.prev, (function(rule) {
      if ("Atrule" !== rule.type || "import" !== rule.name && "charset" !== rule.name) return this.root.firstAtrulesAllowed = !1, 
      list.remove(item), !0;
    }), this);
    break;

   default:
    {
      const name = cssTree$m.keyword(node.name).basename;
      "keyframes" !== name && "media" !== name && "supports" !== name || (utils$9.hasNoChildren(node.prelude) || utils$9.hasNoChildren(node.block)) && list.remove(item);
    }
  }
};

var Comment$1 = function(data, item, list) {
  list.remove(item);
};

const cssTree$l = cjs;

var Declaration$1 = function(node, item, list) {
  node.value.children && node.value.children.isEmpty ? list.remove(item) : cssTree$l.property(node.property).custom && /\S/.test(node.value.value) && (node.value.value = node.value.value.trim());
};

const utils$8 = utils$a;

var Raw$1 = function(node, item, list) {
  (utils$8.isNodeChildrenList(this.stylesheet, list) || utils$8.isNodeChildrenList(this.block, list)) && list.remove(item);
};

const cssTree$k = cjs, utils$7 = utils$a, {hasOwnProperty: hasOwnProperty$5} = Object.prototype, skipUsageFilteringAtrule = new Set([ "keyframes" ]);

function cleanUnused(selectorList, usageData) {
  return selectorList.children.forEach(((selector, item, list) => {
    let shouldRemove = !1;
    cssTree$k.walk(selector, (function(node) {
      if (null === this.selector || this.selector === selectorList) switch (node.type) {
       case "SelectorList":
        null !== this.function && "not" === this.function.name.toLowerCase() || cleanUnused(node, usageData) && (shouldRemove = !0);
        break;

       case "ClassSelector":
        null === usageData.whitelist || null === usageData.whitelist.classes || hasOwnProperty$5.call(usageData.whitelist.classes, node.name) || (shouldRemove = !0), 
        null !== usageData.blacklist && null !== usageData.blacklist.classes && hasOwnProperty$5.call(usageData.blacklist.classes, node.name) && (shouldRemove = !0);
        break;

       case "IdSelector":
        null === usageData.whitelist || null === usageData.whitelist.ids || hasOwnProperty$5.call(usageData.whitelist.ids, node.name) || (shouldRemove = !0), 
        null !== usageData.blacklist && null !== usageData.blacklist.ids && hasOwnProperty$5.call(usageData.blacklist.ids, node.name) && (shouldRemove = !0);
        break;

       case "TypeSelector":
        "*" !== node.name.charAt(node.name.length - 1) && (null === usageData.whitelist || null === usageData.whitelist.tags || hasOwnProperty$5.call(usageData.whitelist.tags, node.name.toLowerCase()) || (shouldRemove = !0), 
        null !== usageData.blacklist && null !== usageData.blacklist.tags && hasOwnProperty$5.call(usageData.blacklist.tags, node.name.toLowerCase()) && (shouldRemove = !0));
      }
    })), shouldRemove && list.remove(item);
  })), selectorList.children.isEmpty;
}

var Rule$1 = function(node, item, list, options) {
  if (utils$7.hasNoChildren(node.prelude) || utils$7.hasNoChildren(node.block)) return void list.remove(item);
  if (this.atrule && skipUsageFilteringAtrule.has(cssTree$k.keyword(this.atrule.name).basename)) return;
  const {usage} = options;
  !usage || null === usage.whitelist && null === usage.blacklist || (cleanUnused(node.prelude, usage), 
  !utils$7.hasNoChildren(node.prelude)) || list.remove(item);
};

const cssTree$j = cjs, handlers$2 = {
  Atrule: Atrule$3,
  Comment: Comment$1,
  Declaration: Declaration$1,
  Raw: Raw$1,
  Rule: Rule$1,
  TypeSelector: function(node, item, list) {
    if ("*" !== item.data.name) return;
    const nextType = item.next && item.next.data.type;
    "IdSelector" !== nextType && "ClassSelector" !== nextType && "AttributeSelector" !== nextType && "PseudoClassSelector" !== nextType && "PseudoElementSelector" !== nextType || list.remove(item);
  },
  WhiteSpace: function(node, item, list) {
    list.remove(item);
  }
};

var clean_1 = function(ast, options) {
  cssTree$j.walk(ast, {
    leave(node, item, list) {
      handlers$2.hasOwnProperty(node.type) && handlers$2[node.type].call(this, node, item, list, options);
    }
  });
};

var keyframes$1 = function(node) {
  node.block.children.forEach((rule => {
    rule.prelude.children.forEach((simpleselector => {
      simpleselector.children.forEach(((data, item) => {
        "Percentage" === data.type && "100" === data.value ? item.data = {
          type: "TypeSelector",
          loc: data.loc,
          name: "to"
        } : "TypeSelector" === data.type && "from" === data.name && (item.data = {
          type: "Percentage",
          loc: data.loc,
          value: "0"
        });
      }));
    }));
  }));
};

const cssTree$i = cjs, keyframes = keyframes$1;

var Atrule_1 = function(node) {
  "keyframes" === cssTree$i.keyword(node.name).basename && keyframes(node);
};

const blockUnquoteRx = /^(-?\d|--)|[\u0000-\u002c\u002e\u002f\u003A-\u0040\u005B-\u005E\u0060\u007B-\u009f]/;

var AttributeSelector_1 = function(node) {
  const attrValue = node.value;
  attrValue && "String" === attrValue.type && function(value) {
    return "" !== value && "-" !== value && !blockUnquoteRx.test(value);
  }(attrValue.value) && (node.value = {
    type: "Identifier",
    loc: attrValue.loc,
    name: attrValue.value
  });
};

var font$1 = function(node) {
  const list = node.children;
  list.forEachRight((function(node, item) {
    if ("Identifier" === node.type) if ("bold" === node.name) item.data = {
      type: "Number",
      loc: node.loc,
      value: "700"
    }; else if ("normal" === node.name) {
      const prev = item.prev;
      prev && "Operator" === prev.data.type && "/" === prev.data.value && this.remove(prev), 
      this.remove(item);
    }
  })), list.isEmpty && list.insert(list.createItem({
    type: "Identifier",
    name: "normal"
  }));
};

var fontWeight$1 = function(node) {
  const value = node.children.head.data;
  if ("Identifier" === value.type) switch (value.name) {
   case "normal":
    node.children.head.data = {
      type: "Number",
      loc: value.loc,
      value: "400"
    };
    break;

   case "bold":
    node.children.head.data = {
      type: "Number",
      loc: value.loc,
      value: "700"
    };
  }
};

const cssTree$h = cjs;

var background$1 = function(node) {
  function flush() {
    buffer.length || buffer.unshift({
      type: "Number",
      loc: null,
      value: "0"
    }, {
      type: "Number",
      loc: null,
      value: "0"
    }), newValue.push.apply(newValue, buffer), buffer = [];
  }
  let newValue = [], buffer = [];
  node.children.forEach((node => {
    if ("Operator" === node.type && "," === node.value) return flush(), void newValue.push(node);
    ("Identifier" !== node.type || "transparent" !== node.name && "none" !== node.name && "repeat" !== node.name && "scroll" !== node.name) && buffer.push(node);
  })), flush(), node.children = (new cssTree$h.List).fromArray(newValue);
};

var border$1 = function(node) {
  node.children.forEach(((node, item, list) => {
    "Identifier" === node.type && "none" === node.name.toLowerCase() && (list.head === list.tail ? item.data = {
      type: "Number",
      loc: node.loc,
      value: "0"
    } : list.remove(item));
  }));
};

const cssTree$g = cjs, handlers$1 = {
  "font": font$1,
  "font-weight": fontWeight$1,
  "background": background$1,
  "border": border$1,
  "outline": border$1
};

var Value$1 = function(node) {
  if (!this.declaration) return;
  const property = cssTree$g.property(this.declaration.property);
  handlers$1.hasOwnProperty(property.basename) && handlers$1[property.basename](node);
}, _Number$4 = {};

const OMIT_PLUSSIGN = /^(?:\+|(-))?0*(\d*)(?:\.0*|(\.\d*?)0*)?$/, KEEP_PLUSSIGN = /^([\+\-])?0*(\d*)(?:\.0*|(\.\d*?)0*)?$/, unsafeToRemovePlusSignAfter = new Set([ "Dimension", "Hash", "Identifier", "Number", "Raw", "UnicodeRange" ]);

function packNumber(value, item) {
  const regexp = item && null !== item.prev && unsafeToRemovePlusSignAfter.has(item.prev.data.type) ? KEEP_PLUSSIGN : OMIT_PLUSSIGN;
  return "" !== (value = String(value).replace(regexp, "$1$2$3")) && "-" !== value || (value = "0"), 
  value;
}

_Number$4.Number = function(node) {
  node.value = packNumber(node.value);
}, _Number$4.packNumber = packNumber;

const _Number$3 = _Number$4, MATH_FUNCTIONS = new Set([ "calc", "min", "max", "clamp" ]), LENGTH_UNIT = new Set([ "px", "mm", "cm", "in", "pt", "pc", "em", "ex", "ch", "rem", "vh", "vw", "vmin", "vmax", "vm" ]);

var Dimension$1 = function(node, item) {
  const value = _Number$3.packNumber(node.value);
  if (node.value = value, "0" === value && null !== this.declaration && null === this.atrulePrelude) {
    const unit = node.unit.toLowerCase();
    if (!LENGTH_UNIT.has(unit)) return;
    if ("-ms-flex" === this.declaration.property || "flex" === this.declaration.property) return;
    if (this.function && MATH_FUNCTIONS.has(this.function.name)) return;
    item.data = {
      type: "Number",
      loc: node.loc,
      value
    };
  }
};

const cssTree$f = cjs, _Number$2 = _Number$4, blacklist = new Set([ "width", "min-width", "max-width", "height", "min-height", "max-height", "flex", "-ms-flex" ]);

var Percentage$1 = function(node, item) {
  node.value = _Number$2.packNumber(node.value), "0" === node.value && this.declaration && !blacklist.has(this.declaration.property) && (item.data = {
    type: "Number",
    loc: node.loc,
    value: node.value
  }, cssTree$f.lexer.matchDeclaration(this.declaration).isType(item.data, "length") || (item.data = node));
};

var Url_1 = function(node) {
  node.value = node.value.replace(/\\/g, "/");
}, color$1 = {};

const cssTree$e = cjs, _Number$1 = _Number$4, NAME_TO_HEX = {
  "aliceblue": "f0f8ff",
  "antiquewhite": "faebd7",
  "aqua": "0ff",
  "aquamarine": "7fffd4",
  "azure": "f0ffff",
  "beige": "f5f5dc",
  "bisque": "ffe4c4",
  "black": "000",
  "blanchedalmond": "ffebcd",
  "blue": "00f",
  "blueviolet": "8a2be2",
  "brown": "a52a2a",
  "burlywood": "deb887",
  "cadetblue": "5f9ea0",
  "chartreuse": "7fff00",
  "chocolate": "d2691e",
  "coral": "ff7f50",
  "cornflowerblue": "6495ed",
  "cornsilk": "fff8dc",
  "crimson": "dc143c",
  "cyan": "0ff",
  "darkblue": "00008b",
  "darkcyan": "008b8b",
  "darkgoldenrod": "b8860b",
  "darkgray": "a9a9a9",
  "darkgrey": "a9a9a9",
  "darkgreen": "006400",
  "darkkhaki": "bdb76b",
  "darkmagenta": "8b008b",
  "darkolivegreen": "556b2f",
  "darkorange": "ff8c00",
  "darkorchid": "9932cc",
  "darkred": "8b0000",
  "darksalmon": "e9967a",
  "darkseagreen": "8fbc8f",
  "darkslateblue": "483d8b",
  "darkslategray": "2f4f4f",
  "darkslategrey": "2f4f4f",
  "darkturquoise": "00ced1",
  "darkviolet": "9400d3",
  "deeppink": "ff1493",
  "deepskyblue": "00bfff",
  "dimgray": "696969",
  "dimgrey": "696969",
  "dodgerblue": "1e90ff",
  "firebrick": "b22222",
  "floralwhite": "fffaf0",
  "forestgreen": "228b22",
  "fuchsia": "f0f",
  "gainsboro": "dcdcdc",
  "ghostwhite": "f8f8ff",
  "gold": "ffd700",
  "goldenrod": "daa520",
  "gray": "808080",
  "grey": "808080",
  "green": "008000",
  "greenyellow": "adff2f",
  "honeydew": "f0fff0",
  "hotpink": "ff69b4",
  "indianred": "cd5c5c",
  "indigo": "4b0082",
  "ivory": "fffff0",
  "khaki": "f0e68c",
  "lavender": "e6e6fa",
  "lavenderblush": "fff0f5",
  "lawngreen": "7cfc00",
  "lemonchiffon": "fffacd",
  "lightblue": "add8e6",
  "lightcoral": "f08080",
  "lightcyan": "e0ffff",
  "lightgoldenrodyellow": "fafad2",
  "lightgray": "d3d3d3",
  "lightgrey": "d3d3d3",
  "lightgreen": "90ee90",
  "lightpink": "ffb6c1",
  "lightsalmon": "ffa07a",
  "lightseagreen": "20b2aa",
  "lightskyblue": "87cefa",
  "lightslategray": "789",
  "lightslategrey": "789",
  "lightsteelblue": "b0c4de",
  "lightyellow": "ffffe0",
  "lime": "0f0",
  "limegreen": "32cd32",
  "linen": "faf0e6",
  "magenta": "f0f",
  "maroon": "800000",
  "mediumaquamarine": "66cdaa",
  "mediumblue": "0000cd",
  "mediumorchid": "ba55d3",
  "mediumpurple": "9370db",
  "mediumseagreen": "3cb371",
  "mediumslateblue": "7b68ee",
  "mediumspringgreen": "00fa9a",
  "mediumturquoise": "48d1cc",
  "mediumvioletred": "c71585",
  "midnightblue": "191970",
  "mintcream": "f5fffa",
  "mistyrose": "ffe4e1",
  "moccasin": "ffe4b5",
  "navajowhite": "ffdead",
  "navy": "000080",
  "oldlace": "fdf5e6",
  "olive": "808000",
  "olivedrab": "6b8e23",
  "orange": "ffa500",
  "orangered": "ff4500",
  "orchid": "da70d6",
  "palegoldenrod": "eee8aa",
  "palegreen": "98fb98",
  "paleturquoise": "afeeee",
  "palevioletred": "db7093",
  "papayawhip": "ffefd5",
  "peachpuff": "ffdab9",
  "peru": "cd853f",
  "pink": "ffc0cb",
  "plum": "dda0dd",
  "powderblue": "b0e0e6",
  "purple": "800080",
  "rebeccapurple": "639",
  "red": "f00",
  "rosybrown": "bc8f8f",
  "royalblue": "4169e1",
  "saddlebrown": "8b4513",
  "salmon": "fa8072",
  "sandybrown": "f4a460",
  "seagreen": "2e8b57",
  "seashell": "fff5ee",
  "sienna": "a0522d",
  "silver": "c0c0c0",
  "skyblue": "87ceeb",
  "slateblue": "6a5acd",
  "slategray": "708090",
  "slategrey": "708090",
  "snow": "fffafa",
  "springgreen": "00ff7f",
  "steelblue": "4682b4",
  "tan": "d2b48c",
  "teal": "008080",
  "thistle": "d8bfd8",
  "tomato": "ff6347",
  "turquoise": "40e0d0",
  "violet": "ee82ee",
  "wheat": "f5deb3",
  "white": "fff",
  "whitesmoke": "f5f5f5",
  "yellow": "ff0",
  "yellowgreen": "9acd32"
}, HEX_TO_NAME = {
  800000: "maroon",
  800080: "purple",
  808000: "olive",
  808080: "gray",
  "00ffff": "cyan",
  "f0ffff": "azure",
  "f5f5dc": "beige",
  "ffe4c4": "bisque",
  "000000": "black",
  "0000ff": "blue",
  "a52a2a": "brown",
  "ff7f50": "coral",
  "ffd700": "gold",
  "008000": "green",
  "4b0082": "indigo",
  "fffff0": "ivory",
  "f0e68c": "khaki",
  "00ff00": "lime",
  "faf0e6": "linen",
  "000080": "navy",
  "ffa500": "orange",
  "da70d6": "orchid",
  "cd853f": "peru",
  "ffc0cb": "pink",
  "dda0dd": "plum",
  "f00": "red",
  "ff0000": "red",
  "fa8072": "salmon",
  "a0522d": "sienna",
  "c0c0c0": "silver",
  "fffafa": "snow",
  "d2b48c": "tan",
  "008080": "teal",
  "ff6347": "tomato",
  "ee82ee": "violet",
  "f5deb3": "wheat",
  "ffffff": "white",
  "ffff00": "yellow"
};

function hueToRgb(p, q, t) {
  return t < 0 && (t += 1), t > 1 && (t -= 1), t < 1 / 6 ? p + 6 * (q - p) * t : t < .5 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p;
}

function hslToRgb(h, s, l, a) {
  let r, g, b;
  if (0 === s) r = g = b = l; else {
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
    r = hueToRgb(p, q, h + 1 / 3), g = hueToRgb(p, q, h), b = hueToRgb(p, q, h - 1 / 3);
  }
  return [ Math.round(255 * r), Math.round(255 * g), Math.round(255 * b), a ];
}

function toHex(value) {
  return 1 === (value = value.toString(16)).length ? "0" + value : value;
}

function parseFunctionArgs(functionArgs, count, rgb) {
  let cursor = functionArgs.head, args = [], wasValue = !1;
  for (;null !== cursor; ) {
    const {type, value} = cursor.data;
    switch (type) {
     case "Number":
     case "Percentage":
      if (wasValue) return;
      wasValue = !0, args.push({
        type,
        value: Number(value)
      });
      break;

     case "Operator":
      if ("," === value) {
        if (!wasValue) return;
        wasValue = !1;
      } else if (wasValue || "+" !== value) return;
      break;

     default:
      return;
    }
    cursor = cursor.next;
  }
  if (args.length === count) {
    if (4 === args.length) {
      if ("Number" !== args[3].type) return;
      args[3].type = "Alpha";
    }
    if (rgb) {
      if (args[0].type !== args[1].type || args[0].type !== args[2].type) return;
    } else {
      if ("Number" !== args[0].type || "Percentage" !== args[1].type || "Percentage" !== args[2].type) return;
      args[0].type = "Angle";
    }
    return args.map((function(arg) {
      let value = Math.max(0, arg.value);
      switch (arg.type) {
       case "Number":
        value = Math.min(value, 255);
        break;

       case "Percentage":
        if (value = Math.min(value, 100) / 100, !rgb) return value;
        value *= 255;
        break;

       case "Angle":
        return (value % 360 + 360) % 360 / 360;

       case "Alpha":
        return Math.min(value, 1);
      }
      return Math.round(value);
    }));
  }
}

function compressHex(node, item) {
  let color = node.value.toLowerCase();
  6 === color.length && color[0] === color[1] && color[2] === color[3] && color[4] === color[5] && (color = color[0] + color[2] + color[4]), 
  HEX_TO_NAME[color] ? item.data = {
    type: "Identifier",
    loc: node.loc,
    name: HEX_TO_NAME[color]
  } : node.value = color;
}

color$1.compressFunction = function(node, item) {
  let args, functionName = node.name;
  if ("rgba" === functionName || "hsla" === functionName) {
    if (args = parseFunctionArgs(node.children, 4, "rgba" === functionName), !args) return;
    if ("hsla" === functionName && (args = hslToRgb(...args), node.name = "rgba"), 0 === args[3]) {
      const scopeFunctionName = this.function && this.function.name;
      if (0 === args[0] && 0 === args[1] && 0 === args[2] || !/^(?:to|from|color-stop)$|gradient$/i.test(scopeFunctionName)) return void (item.data = {
        type: "Identifier",
        loc: node.loc,
        name: "transparent"
      });
    }
    if (1 !== args[3]) return void node.children.forEach(((node, item, list) => {
      "Operator" !== node.type ? item.data = {
        type: "Number",
        loc: node.loc,
        value: _Number$1.packNumber(args.shift())
      } : "," !== node.value && list.remove(item);
    }));
    functionName = "rgb";
  }
  if ("hsl" === functionName) {
    if (args = args || parseFunctionArgs(node.children, 3, !1), !args) return;
    args = hslToRgb(...args), functionName = "rgb";
  }
  if ("rgb" === functionName) {
    if (args = args || parseFunctionArgs(node.children, 3, !0), !args) return;
    item.data = {
      type: "Hash",
      loc: node.loc,
      value: toHex(args[0]) + toHex(args[1]) + toHex(args[2])
    }, compressHex(item.data, item);
  }
}, color$1.compressHex = compressHex, color$1.compressIdent = function(node, item) {
  if (null === this.declaration) return;
  let color = node.name.toLowerCase();
  if (NAME_TO_HEX.hasOwnProperty(color) && cssTree$e.lexer.matchDeclaration(this.declaration).isType(node, "color")) {
    const hex = NAME_TO_HEX[color];
    hex.length + 1 <= color.length ? item.data = {
      type: "Hash",
      loc: node.loc,
      value: hex
    } : ("grey" === color && (color = "gray"), node.name = color);
  }
};

const cssTree$d = cjs, Url = Url_1, color = color$1, handlers = {
  Atrule: Atrule_1,
  AttributeSelector: AttributeSelector_1,
  Value: Value$1,
  Dimension: Dimension$1,
  Percentage: Percentage$1,
  Number: _Number$4.Number,
  Url,
  Hash: color.compressHex,
  Identifier: color.compressIdent,
  Function: color.compressFunction
};

var replace_1 = function(ast) {
  cssTree$d.walk(ast, {
    leave(node, item, list) {
      handlers.hasOwnProperty(node.type) && handlers[node.type].call(this, node, item, list);
    }
  });
};

const cssTree$c = cjs;

class Index {
  constructor() {
    this.map = new Map;
  }
  resolve(str) {
    let index = this.map.get(str);
    return void 0 === index && (index = this.map.size + 1, this.map.set(str, index)), 
    index;
  }
}

var createDeclarationIndexer_1 = function() {
  const ids = new Index;
  return function(node) {
    const id = cssTree$c.generate(node);
    return node.id = ids.resolve(id), node.length = id.length, node.fingerprint = null, 
    node;
  };
};

const cssTree$b = cjs;

function maxSelectorListSpecificity(selectorList) {
  return function(node) {
    return "Raw" === node.type ? cssTree$b.parse(node.value, {
      context: "selectorList"
    }) : node;
  }(selectorList).children.reduce(((result, node) => function(a, b) {
    for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i] ? a : b;
    return a;
  }(specificity$4(node), result)), [ 0, 0, 0 ]);
}

function specificity$4(simpleSelector) {
  let A = 0, B = 0, C = 0;
  return simpleSelector.children.forEach((node => {
    switch (node.type) {
     case "IdSelector":
      A++;
      break;

     case "ClassSelector":
     case "AttributeSelector":
      B++;
      break;

     case "PseudoClassSelector":
      switch (node.name.toLowerCase()) {
       case "not":
       case "has":
       case "is":
       case "matches":
       case "-webkit-any":
       case "-moz-any":
        {
          const [a, b, c] = maxSelectorListSpecificity(node.children.first);
          A += a, B += b, C += c;
          break;
        }

       case "nth-child":
       case "nth-last-child":
        {
          const arg = node.children.first;
          if ("Nth" === arg.type && arg.selector) {
            const [a, b, c] = maxSelectorListSpecificity(arg.selector);
            A += a, B += b + 1, C += c;
          } else B++;
          break;
        }

       case "where":
        break;

       case "before":
       case "after":
       case "first-line":
       case "first-letter":
        C++;
        break;

       default:
        B++;
      }
      break;

     case "TypeSelector":
      node.name.endsWith("*") || C++;
      break;

     case "PseudoElementSelector":
      C++;
    }
  })), [ A, B, C ];
}

var specificity_1 = specificity$4;

const cssTree$a = cjs, specificity$3 = specificity_1, nonFreezePseudoElements = new Set([ "first-letter", "first-line", "after", "before" ]), nonFreezePseudoClasses = new Set([ "link", "visited", "hover", "active", "first-letter", "first-line", "after", "before" ]);

var processSelector_1 = function(node, usageData) {
  const pseudos = new Set;
  node.prelude.children.forEach((function(simpleSelector) {
    let tagName = "*", scope = 0;
    simpleSelector.children.forEach((function(node) {
      switch (node.type) {
       case "ClassSelector":
        if (usageData && usageData.scopes) {
          const classScope = usageData.scopes[node.name] || 0;
          if (0 !== scope && classScope !== scope) throw new Error("Selector can't has classes from different scopes: " + cssTree$a.generate(simpleSelector));
          scope = classScope;
        }
        break;

       case "PseudoClassSelector":
        {
          const name = node.name.toLowerCase();
          nonFreezePseudoClasses.has(name) || pseudos.add(`:${name}`);
          break;
        }

       case "PseudoElementSelector":
        {
          const name = node.name.toLowerCase();
          nonFreezePseudoElements.has(name) || pseudos.add(`::${name}`);
          break;
        }

       case "TypeSelector":
        tagName = node.name.toLowerCase();
        break;

       case "AttributeSelector":
        node.flags && pseudos.add(`[${node.flags.toLowerCase()}]`);
        break;

       case "Combinator":
        tagName = "*";
      }
    })), simpleSelector.compareMarker = specificity$3(simpleSelector).toString(), simpleSelector.id = null, 
    simpleSelector.id = cssTree$a.generate(simpleSelector), scope && (simpleSelector.compareMarker += ":" + scope), 
    "*" !== tagName && (simpleSelector.compareMarker += "," + tagName);
  })), node.pseudoSignature = pseudos.size > 0 && [ ...pseudos ].sort().join(",");
};

const cssTree$9 = cjs, createDeclarationIndexer = createDeclarationIndexer_1, processSelector$1 = processSelector_1;

var prepare_1 = function(ast, options) {
  const markDeclaration = createDeclarationIndexer();
  return cssTree$9.walk(ast, {
    visit: "Rule",
    enter(node) {
      node.block.children.forEach(markDeclaration), processSelector$1(node, options.usage);
    }
  }), cssTree$9.walk(ast, {
    visit: "Atrule",
    enter(node) {
      node.prelude && (node.prelude.id = null, node.prelude.id = cssTree$9.generate(node.prelude)), 
      "keyframes" === cssTree$9.keyword(node.name).basename && (node.block.avoidRulesMerge = !0, 
      node.block.children.forEach((function(rule) {
        rule.prelude.children.forEach((function(simpleselector) {
          simpleselector.compareMarker = simpleselector.id;
        }));
      })));
    }
  }), {
    declaration: markDeclaration
  };
};

const cssTree$8 = cjs, {hasOwnProperty: hasOwnProperty$4} = Object.prototype;

function addRuleToMap(map, item, list, single) {
  const node = item.data, name = cssTree$8.keyword(node.name).basename, id = node.name.toLowerCase() + "/" + (node.prelude ? node.prelude.id : null);
  hasOwnProperty$4.call(map, name) || (map[name] = Object.create(null)), single && delete map[name][id], 
  hasOwnProperty$4.call(map[name], id) || (map[name][id] = new cssTree$8.List), map[name][id].append(list.remove(item));
}

function isMediaRule(node) {
  return "Atrule" === node.type && "media" === node.name;
}

function processAtrule(node, item, list) {
  if (!isMediaRule(node)) return;
  const prev = item.prev && item.prev.data;
  prev && isMediaRule(prev) && node.prelude && prev.prelude && node.prelude.id === prev.prelude.id && (prev.block.children.appendList(node.block.children), 
  list.remove(item));
}

var _1MergeAtrule$1 = function(ast, options) {
  !function(ast, options) {
    const collected = Object.create(null);
    let topInjectPoint = null;
    ast.children.forEach((function(node, item, list) {
      if ("Atrule" === node.type) {
        const name = cssTree$8.keyword(node.name).basename;
        switch (name) {
         case "keyframes":
          return void addRuleToMap(collected, item, list, !0);

         case "media":
          if (options.forceMediaMerge) return void addRuleToMap(collected, item, list, !1);
        }
        null === topInjectPoint && "charset" !== name && "import" !== name && (topInjectPoint = item);
      } else null === topInjectPoint && (topInjectPoint = item);
    }));
    for (const atrule in collected) for (const id in collected[atrule]) ast.children.insertList(collected[atrule][id], "media" === atrule ? null : topInjectPoint);
  }(ast, options), cssTree$8.walk(ast, {
    visit: "Atrule",
    reverse: !0,
    enter: processAtrule
  });
}, utils$6 = {};

const {hasOwnProperty: hasOwnProperty$3} = Object.prototype;

function hasSimilarSelectors(selectors1, selectors2) {
  let cursor1 = selectors1.head;
  for (;null !== cursor1; ) {
    let cursor2 = selectors2.head;
    for (;null !== cursor2; ) {
      if (cursor1.data.compareMarker === cursor2.data.compareMarker) return !0;
      cursor2 = cursor2.next;
    }
    cursor1 = cursor1.next;
  }
  return !1;
}

utils$6.addSelectors = function(dest, source) {
  return source.forEach((sourceData => {
    const newStr = sourceData.id;
    let cursor = dest.head;
    for (;cursor; ) {
      const nextStr = cursor.data.id;
      if (nextStr === newStr) return;
      if (nextStr > newStr) break;
      cursor = cursor.next;
    }
    dest.insert(dest.createItem(sourceData), cursor);
  })), dest;
}, utils$6.compareDeclarations = function(declarations1, declarations2) {
  const result = {
    eq: [],
    ne1: [],
    ne2: [],
    ne2overrided: []
  }, fingerprints = Object.create(null), declarations2hash = Object.create(null);
  for (let cursor = declarations2.head; cursor; cursor = cursor.next) declarations2hash[cursor.data.id] = !0;
  for (let cursor = declarations1.head; cursor; cursor = cursor.next) {
    const data = cursor.data;
    data.fingerprint && (fingerprints[data.fingerprint] = data.important), declarations2hash[data.id] ? (declarations2hash[data.id] = !1, 
    result.eq.push(data)) : result.ne1.push(data);
  }
  for (let cursor = declarations2.head; cursor; cursor = cursor.next) {
    const data = cursor.data;
    declarations2hash[data.id] && ((!hasOwnProperty$3.call(fingerprints, data.fingerprint) || !fingerprints[data.fingerprint] && data.important) && result.ne2.push(data), 
    result.ne2overrided.push(data));
  }
  return result;
}, utils$6.hasSimilarSelectors = hasSimilarSelectors, utils$6.isEqualDeclarations = function(a, b) {
  let cursor1 = a.head, cursor2 = b.head;
  for (;null !== cursor1 && null !== cursor2 && cursor1.data.id === cursor2.data.id; ) cursor1 = cursor1.next, 
  cursor2 = cursor2.next;
  return null === cursor1 && null === cursor2;
}, utils$6.isEqualSelectors = function(a, b) {
  let cursor1 = a.head, cursor2 = b.head;
  for (;null !== cursor1 && null !== cursor2 && cursor1.data.id === cursor2.data.id; ) cursor1 = cursor1.next, 
  cursor2 = cursor2.next;
  return null === cursor1 && null === cursor2;
}, utils$6.unsafeToSkipNode = function unsafeToSkipNode(node) {
  switch (node.type) {
   case "Rule":
    return hasSimilarSelectors(node.prelude.children, this);

   case "Atrule":
    if (node.block) return node.block.children.some(unsafeToSkipNode, this);
    break;

   case "Declaration":
    return !1;
  }
  return !0;
};

const cssTree$7 = cjs, utils$5 = utils$6;

function processRule$5(node, item, list) {
  const selectors = node.prelude.children, declarations = node.block.children;
  list.prevUntil(item.prev, (function(prev) {
    if ("Rule" !== prev.type) return utils$5.unsafeToSkipNode.call(selectors, prev);
    const prevSelectors = prev.prelude.children, prevDeclarations = prev.block.children;
    if (node.pseudoSignature === prev.pseudoSignature) {
      if (utils$5.isEqualSelectors(prevSelectors, selectors)) return prevDeclarations.appendList(declarations), 
      list.remove(item), !0;
      if (utils$5.isEqualDeclarations(declarations, prevDeclarations)) return utils$5.addSelectors(prevSelectors, selectors), 
      list.remove(item), !0;
    }
    return utils$5.hasSimilarSelectors(selectors, prevSelectors);
  }));
}

var _2InitialMergeRuleset$1 = function(ast) {
  cssTree$7.walk(ast, {
    visit: "Rule",
    enter: processRule$5
  });
};

const cssTree$6 = cjs;

function processRule$4(node, item, list) {
  const selectors = node.prelude.children;
  for (;selectors.head !== selectors.tail; ) {
    const newSelectors = new cssTree$6.List;
    newSelectors.insert(selectors.remove(selectors.head)), list.insert(list.createItem({
      type: "Rule",
      loc: node.loc,
      prelude: {
        type: "SelectorList",
        loc: node.prelude.loc,
        children: newSelectors
      },
      block: {
        type: "Block",
        loc: node.block.loc,
        children: node.block.children.copy()
      },
      pseudoSignature: node.pseudoSignature
    }), item);
  }
}

var _3DisjoinRuleset$1 = function(ast) {
  cssTree$6.walk(ast, {
    visit: "Rule",
    reverse: !0,
    enter: processRule$4
  });
};

const cssTree$5 = cjs, REPLACE = 1, REMOVE = 2, SIDES = [ "top", "right", "bottom", "left" ], SIDE = {
  "margin-top": "top",
  "margin-right": "right",
  "margin-bottom": "bottom",
  "margin-left": "left",
  "padding-top": "top",
  "padding-right": "right",
  "padding-bottom": "bottom",
  "padding-left": "left",
  "border-top-color": "top",
  "border-right-color": "right",
  "border-bottom-color": "bottom",
  "border-left-color": "left",
  "border-top-width": "top",
  "border-right-width": "right",
  "border-bottom-width": "bottom",
  "border-left-width": "left",
  "border-top-style": "top",
  "border-right-style": "right",
  "border-bottom-style": "bottom",
  "border-left-style": "left"
}, MAIN_PROPERTY = {
  "margin": "margin",
  "margin-top": "margin",
  "margin-right": "margin",
  "margin-bottom": "margin",
  "margin-left": "margin",
  "padding": "padding",
  "padding-top": "padding",
  "padding-right": "padding",
  "padding-bottom": "padding",
  "padding-left": "padding",
  "border-color": "border-color",
  "border-top-color": "border-color",
  "border-right-color": "border-color",
  "border-bottom-color": "border-color",
  "border-left-color": "border-color",
  "border-width": "border-width",
  "border-top-width": "border-width",
  "border-right-width": "border-width",
  "border-bottom-width": "border-width",
  "border-left-width": "border-width",
  "border-style": "border-style",
  "border-top-style": "border-style",
  "border-right-style": "border-style",
  "border-bottom-style": "border-style",
  "border-left-style": "border-style"
};

class TRBL {
  constructor(name) {
    this.name = name, this.loc = null, this.iehack = void 0, this.sides = {
      "top": null,
      "right": null,
      "bottom": null,
      "left": null
    };
  }
  getValueSequence(declaration, count) {
    const values = [];
    let iehack = "";
    return !("Value" !== declaration.value.type || declaration.value.children.some((function(child) {
      let special = !1;
      switch (child.type) {
       case "Identifier":
        switch (child.name) {
         case "\\0":
         case "\\9":
          return void (iehack = child.name);

         case "inherit":
         case "initial":
         case "unset":
         case "revert":
          special = child.name;
        }
        break;

       case "Dimension":
        switch (child.unit) {
         case "rem":
         case "vw":
         case "vh":
         case "vmin":
         case "vmax":
         case "vm":
          special = child.unit;
        }
        break;

       case "Hash":
       case "Number":
       case "Percentage":
        break;

       case "Function":
        if ("var" === child.name) return !0;
        special = child.name;
        break;

       default:
        return !0;
      }
      values.push({
        node: child,
        special,
        important: declaration.important
      });
    })) || values.length > count) && (("string" != typeof this.iehack || this.iehack === iehack) && (this.iehack = iehack, 
    values));
  }
  canOverride(side, value) {
    const currentValue = this.sides[side];
    return !currentValue || value.important && !currentValue.important;
  }
  add(name, declaration) {
    return !!function() {
      const sides = this.sides, side = SIDE[name];
      if (side) {
        if (side in sides == !1) return !1;
        const values = this.getValueSequence(declaration, 1);
        if (!values || !values.length) return !1;
        for (const key in sides) if (null !== sides[key] && sides[key].special !== values[0].special) return !1;
        return !this.canOverride(side, values[0]) || (sides[side] = values[0], !0);
      }
      if (name === this.name) {
        const values = this.getValueSequence(declaration, 4);
        if (!values || !values.length) return !1;
        switch (values.length) {
         case 1:
          values[1] = values[0], values[2] = values[0], values[3] = values[0];
          break;

         case 2:
          values[2] = values[0], values[3] = values[1];
          break;

         case 3:
          values[3] = values[1];
        }
        for (let i = 0; i < 4; i++) for (const key in sides) if (null !== sides[key] && sides[key].special !== values[i].special) return !1;
        for (let i = 0; i < 4; i++) this.canOverride(SIDES[i], values[i]) && (sides[SIDES[i]] = values[i]);
        return !0;
      }
    }.call(this) && (this.loc || (this.loc = declaration.loc), !0);
  }
  isOkToMinimize() {
    const top = this.sides.top, right = this.sides.right, bottom = this.sides.bottom, left = this.sides.left;
    if (top && right && bottom && left) {
      const important = top.important + right.important + bottom.important + left.important;
      return 0 === important || 4 === important;
    }
    return !1;
  }
  getValue() {
    const result = new cssTree$5.List, sides = this.sides, values = [ sides.top, sides.right, sides.bottom, sides.left ], stringValues = [ cssTree$5.generate(sides.top.node), cssTree$5.generate(sides.right.node), cssTree$5.generate(sides.bottom.node), cssTree$5.generate(sides.left.node) ];
    stringValues[3] === stringValues[1] && (values.pop(), stringValues[2] === stringValues[0] && (values.pop(), 
    stringValues[1] === stringValues[0] && values.pop()));
    for (let i = 0; i < values.length; i++) result.appendData(values[i].node);
    return this.iehack && result.appendData({
      type: "Identifier",
      loc: null,
      name: this.iehack
    }), {
      type: "Value",
      loc: null,
      children: result
    };
  }
  getDeclaration() {
    return {
      type: "Declaration",
      loc: this.loc,
      important: this.sides.top.important,
      property: this.name,
      value: this.getValue()
    };
  }
}

function processRule$3(rule, shorts, shortDeclarations, lastShortSelector) {
  const declarations = rule.block.children, selector = rule.prelude.children.first.id;
  return rule.block.children.forEachRight((function(declaration, item) {
    const property = declaration.property;
    if (!MAIN_PROPERTY.hasOwnProperty(property)) return;
    const key = MAIN_PROPERTY[property];
    let shorthand, operation;
    lastShortSelector && selector !== lastShortSelector || key in shorts && (operation = REMOVE, 
    shorthand = shorts[key]), shorthand && shorthand.add(property, declaration) || (operation = REPLACE, 
    shorthand = new TRBL(key), shorthand.add(property, declaration)) ? (shorts[key] = shorthand, 
    shortDeclarations.push({
      operation,
      block: declarations,
      item,
      shorthand
    }), lastShortSelector = selector) : lastShortSelector = null;
  })), lastShortSelector;
}

var _4RestructShorthand$1 = function(ast, indexer) {
  const stylesheetMap = {}, shortDeclarations = [];
  cssTree$5.walk(ast, {
    visit: "Rule",
    reverse: !0,
    enter(node) {
      const stylesheet = this.block || this.stylesheet, ruleId = (node.pseudoSignature || "") + "|" + node.prelude.children.first.id;
      let ruleMap, shorts;
      stylesheetMap.hasOwnProperty(stylesheet.id) ? ruleMap = stylesheetMap[stylesheet.id] : (ruleMap = {
        lastShortSelector: null
      }, stylesheetMap[stylesheet.id] = ruleMap), ruleMap.hasOwnProperty(ruleId) ? shorts = ruleMap[ruleId] : (shorts = {}, 
      ruleMap[ruleId] = shorts), ruleMap.lastShortSelector = processRule$3.call(this, node, shorts, shortDeclarations, ruleMap.lastShortSelector);
    }
  }), function(shortDeclarations, markDeclaration) {
    shortDeclarations.forEach((function(item) {
      const shorthand = item.shorthand;
      shorthand.isOkToMinimize() && (item.operation === REPLACE ? item.item.data = markDeclaration(shorthand.getDeclaration()) : item.block.remove(item.item));
    }));
  }(shortDeclarations, indexer.declaration);
};

const cssTree$4 = cjs;

let fingerprintId = 1;

const dontRestructure = new Set([ "src" ]), DONT_MIX_VALUE = {
  "display": /table|ruby|flex|-(flex)?box$|grid|contents|run-in/i,
  "text-align": /^(start|end|match-parent|justify-all)$/i
}, SAFE_VALUES = {
  cursor: [ "auto", "crosshair", "default", "move", "text", "wait", "help", "n-resize", "e-resize", "s-resize", "w-resize", "ne-resize", "nw-resize", "se-resize", "sw-resize", "pointer", "progress", "not-allowed", "no-drop", "vertical-text", "all-scroll", "col-resize", "row-resize" ],
  overflow: [ "hidden", "visible", "scroll", "auto" ],
  position: [ "static", "relative", "absolute", "fixed" ]
}, NEEDLESS_TABLE = {
  "border-width": [ "border" ],
  "border-style": [ "border" ],
  "border-color": [ "border" ],
  "border-top": [ "border" ],
  "border-right": [ "border" ],
  "border-bottom": [ "border" ],
  "border-left": [ "border" ],
  "border-top-width": [ "border-top", "border-width", "border" ],
  "border-right-width": [ "border-right", "border-width", "border" ],
  "border-bottom-width": [ "border-bottom", "border-width", "border" ],
  "border-left-width": [ "border-left", "border-width", "border" ],
  "border-top-style": [ "border-top", "border-style", "border" ],
  "border-right-style": [ "border-right", "border-style", "border" ],
  "border-bottom-style": [ "border-bottom", "border-style", "border" ],
  "border-left-style": [ "border-left", "border-style", "border" ],
  "border-top-color": [ "border-top", "border-color", "border" ],
  "border-right-color": [ "border-right", "border-color", "border" ],
  "border-bottom-color": [ "border-bottom", "border-color", "border" ],
  "border-left-color": [ "border-left", "border-color", "border" ],
  "margin-top": [ "margin" ],
  "margin-right": [ "margin" ],
  "margin-bottom": [ "margin" ],
  "margin-left": [ "margin" ],
  "padding-top": [ "padding" ],
  "padding-right": [ "padding" ],
  "padding-bottom": [ "padding" ],
  "padding-left": [ "padding" ],
  "font-style": [ "font" ],
  "font-variant": [ "font" ],
  "font-weight": [ "font" ],
  "font-size": [ "font" ],
  "font-family": [ "font" ],
  "list-style-type": [ "list-style" ],
  "list-style-position": [ "list-style" ],
  "list-style-image": [ "list-style" ]
};

function getPropertyFingerprint(propertyName, declaration, fingerprints) {
  const realName = cssTree$4.property(propertyName).basename;
  if ("background" === realName) return propertyName + ":" + cssTree$4.generate(declaration.value);
  const declarationId = declaration.id;
  let fingerprint = fingerprints[declarationId];
  if (!fingerprint) {
    switch (declaration.value.type) {
     case "Value":
      const special = {};
      let vendorId = "", iehack = "", raw = !1;
      declaration.value.children.forEach((function walk(node) {
        switch (node.type) {
         case "Value":
         case "Brackets":
         case "Parentheses":
          node.children.forEach(walk);
          break;

         case "Raw":
          raw = !0;
          break;

         case "Identifier":
          {
            const {name} = node;
            vendorId || (vendorId = cssTree$4.keyword(name).vendor), /\\[09]/.test(name) && (iehack = RegExp.lastMatch), 
            SAFE_VALUES.hasOwnProperty(realName) ? -1 === SAFE_VALUES[realName].indexOf(name) && (special[name] = !0) : DONT_MIX_VALUE.hasOwnProperty(realName) && DONT_MIX_VALUE[realName].test(name) && (special[name] = !0);
            break;
          }

         case "Function":
          {
            let {name} = node;
            if (vendorId || (vendorId = cssTree$4.keyword(name).vendor), "rect" === name) {
              node.children.some((node => "Operator" === node.type && "," === node.value)) || (name = "rect-backward");
            }
            special[name + "()"] = !0, node.children.forEach(walk);
            break;
          }

         case "Dimension":
          {
            const {unit} = node;
            switch (/\\[09]/.test(unit) && (iehack = RegExp.lastMatch), unit) {
             case "rem":
             case "vw":
             case "vh":
             case "vmin":
             case "vmax":
             case "vm":
              special[unit] = !0;
            }
            break;
          }
        }
      })), fingerprint = raw ? "!" + fingerprintId++ : "!" + Object.keys(special).sort() + "|" + iehack + vendorId;
      break;

     case "Raw":
      fingerprint = "!" + declaration.value.value;
      break;

     default:
      fingerprint = cssTree$4.generate(declaration.value);
    }
    fingerprints[declarationId] = fingerprint;
  }
  return propertyName + fingerprint;
}

function processRule$2(rule, item, list, props, fingerprints) {
  const declarations = rule.block.children;
  declarations.forEachRight((function(declaration, declarationItem) {
    const {property} = declaration, fingerprint = getPropertyFingerprint(property, declaration, fingerprints), prev = props[fingerprint];
    if (prev && !dontRestructure.has(property)) declaration.important && !prev.item.data.important ? (props[fingerprint] = {
      block: declarations,
      item: declarationItem
    }, prev.block.remove(prev.item)) : declarations.remove(declarationItem); else {
      const prev = function(props, declaration, fingerprints) {
        const property = cssTree$4.property(declaration.property);
        if (NEEDLESS_TABLE.hasOwnProperty(property.basename)) {
          const table = NEEDLESS_TABLE[property.basename];
          for (const entry of table) {
            const ppre = getPropertyFingerprint(property.prefix + entry, declaration, fingerprints), prev = props.hasOwnProperty(ppre) ? props[ppre] : null;
            if (prev && (!declaration.important || prev.item.data.important)) return prev;
          }
        }
      }(props, declaration, fingerprints);
      prev ? declarations.remove(declarationItem) : (declaration.fingerprint = fingerprint, 
      props[fingerprint] = {
        block: declarations,
        item: declarationItem
      });
    }
  })), declarations.isEmpty && list.remove(item);
}

var _6RestructBlock$1 = function(ast) {
  const stylesheetMap = {}, fingerprints = Object.create(null);
  cssTree$4.walk(ast, {
    visit: "Rule",
    reverse: !0,
    enter(node, item, list) {
      const stylesheet = this.block || this.stylesheet, ruleId = (node.pseudoSignature || "") + "|" + node.prelude.children.first.id;
      let ruleMap, props;
      stylesheetMap.hasOwnProperty(stylesheet.id) ? ruleMap = stylesheetMap[stylesheet.id] : (ruleMap = {}, 
      stylesheetMap[stylesheet.id] = ruleMap), ruleMap.hasOwnProperty(ruleId) ? props = ruleMap[ruleId] : (props = {}, 
      ruleMap[ruleId] = props), processRule$2.call(this, node, item, list, props, fingerprints);
    }
  });
};

const cssTree$3 = cjs, utils$4 = utils$6;

function processRule$1(node, item, list) {
  const selectors = node.prelude.children, declarations = node.block.children, nodeCompareMarker = selectors.first.compareMarker, skippedCompareMarkers = {};
  list.nextUntil(item.next, (function(next, nextItem) {
    if ("Rule" !== next.type) return utils$4.unsafeToSkipNode.call(selectors, next);
    if (node.pseudoSignature !== next.pseudoSignature) return !0;
    const nextFirstSelector = next.prelude.children.head, nextDeclarations = next.block.children, nextCompareMarker = nextFirstSelector.data.compareMarker;
    if (nextCompareMarker in skippedCompareMarkers) return !0;
    if (selectors.head === selectors.tail && selectors.first.id === nextFirstSelector.data.id) return declarations.appendList(nextDeclarations), 
    void list.remove(nextItem);
    if (utils$4.isEqualDeclarations(declarations, nextDeclarations)) {
      const nextStr = nextFirstSelector.data.id;
      return selectors.some(((data, item) => {
        const curStr = data.id;
        return nextStr < curStr ? (selectors.insert(nextFirstSelector, item), !0) : item.next ? void 0 : (selectors.insert(nextFirstSelector), 
        !0);
      })), void list.remove(nextItem);
    }
    if (nextCompareMarker === nodeCompareMarker) return !0;
    skippedCompareMarkers[nextCompareMarker] = !0;
  }));
}

const cssTree$2 = cjs, utils$3 = utils$6;

function calcSelectorLength(list) {
  return list.reduce(((res, data) => res + data.id.length + 1), 0) - 1;
}

function calcDeclarationsLength(tokens) {
  let length = 0;
  for (const token of tokens) length += token.length;
  return length + tokens.length - 1;
}

function processRule(node, item, list) {
  const avoidRulesMerge = null !== this.block && this.block.avoidRulesMerge, selectors = node.prelude.children, block = node.block, disallowDownMarkers = Object.create(null);
  let allowMergeUp = !0, allowMergeDown = !0;
  list.prevUntil(item.prev, (function(prev, prevItem) {
    const prevBlock = prev.block, prevType = prev.type;
    if ("Rule" !== prevType) {
      const unsafe = utils$3.unsafeToSkipNode.call(selectors, prev);
      return !unsafe && "Atrule" === prevType && prevBlock && cssTree$2.walk(prevBlock, {
        visit: "Rule",
        enter(node) {
          node.prelude.children.forEach((data => {
            disallowDownMarkers[data.compareMarker] = !0;
          }));
        }
      }), unsafe;
    }
    if (node.pseudoSignature !== prev.pseudoSignature) return !0;
    const prevSelectors = prev.prelude.children;
    if (allowMergeDown = !prevSelectors.some((selector => selector.compareMarker in disallowDownMarkers)), 
    !allowMergeDown && !allowMergeUp) return !0;
    if (allowMergeUp && utils$3.isEqualSelectors(prevSelectors, selectors)) return prevBlock.children.appendList(block.children), 
    list.remove(item), !0;
    const diff = utils$3.compareDeclarations(block.children, prevBlock.children);
    if (diff.eq.length) {
      if (!diff.ne1.length && !diff.ne2.length) return allowMergeDown && (utils$3.addSelectors(selectors, prevSelectors), 
      list.remove(prevItem)), !0;
      if (!avoidRulesMerge) if (diff.ne1.length && !diff.ne2.length) {
        const selectorLength = calcSelectorLength(selectors), blockLength = calcDeclarationsLength(diff.eq);
        allowMergeUp && selectorLength < blockLength && (utils$3.addSelectors(prevSelectors, selectors), 
        block.children.fromArray(diff.ne1));
      } else if (!diff.ne1.length && diff.ne2.length) {
        const selectorLength = calcSelectorLength(prevSelectors), blockLength = calcDeclarationsLength(diff.eq);
        allowMergeDown && selectorLength < blockLength && (utils$3.addSelectors(selectors, prevSelectors), 
        prevBlock.children.fromArray(diff.ne2));
      } else {
        const newSelector = {
          type: "SelectorList",
          loc: null,
          children: utils$3.addSelectors(prevSelectors.copy(), selectors)
        }, newBlockLength = calcSelectorLength(newSelector.children) + 2;
        if (calcDeclarationsLength(diff.eq) >= newBlockLength) {
          const newItem = list.createItem({
            type: "Rule",
            loc: null,
            prelude: newSelector,
            block: {
              type: "Block",
              loc: null,
              children: (new cssTree$2.List).fromArray(diff.eq)
            },
            pseudoSignature: node.pseudoSignature
          });
          return block.children.fromArray(diff.ne1), prevBlock.children.fromArray(diff.ne2overrided), 
          allowMergeUp ? list.insert(newItem, prevItem) : list.insert(newItem, item), !0;
        }
      }
    }
    allowMergeUp && (allowMergeUp = !prevSelectors.some((prevSelector => selectors.some((selector => selector.compareMarker === prevSelector.compareMarker))))), 
    prevSelectors.forEach((data => {
      disallowDownMarkers[data.compareMarker] = !0;
    }));
  }));
}

const index$3 = prepare_1, _1MergeAtrule = _1MergeAtrule$1, _2InitialMergeRuleset = _2InitialMergeRuleset$1, _3DisjoinRuleset = _3DisjoinRuleset$1, _4RestructShorthand = _4RestructShorthand$1, _6RestructBlock = _6RestructBlock$1, _7MergeRuleset = function(ast) {
  cssTree$3.walk(ast, {
    visit: "Rule",
    enter: processRule$1
  });
}, _8RestructRuleset = function(ast) {
  cssTree$2.walk(ast, {
    visit: "Rule",
    reverse: !0,
    enter: processRule
  });
};

const cssTree$1 = cjs, usage = usage$1, index = clean_1, index$1 = replace_1, index$2 = function(ast, options) {
  const indexer = index$3(ast, options);
  options.logger("prepare", ast), _1MergeAtrule(ast, options), options.logger("mergeAtrule", ast), 
  _2InitialMergeRuleset(ast), options.logger("initialMergeRuleset", ast), _3DisjoinRuleset(ast), 
  options.logger("disjoinRuleset", ast), _4RestructShorthand(ast, indexer), options.logger("restructShorthand", ast), 
  _6RestructBlock(ast), options.logger("restructBlock", ast), _7MergeRuleset(ast), 
  options.logger("mergeRuleset", ast), _8RestructRuleset(ast), options.logger("restructRuleset", ast);
};

function readChunk(input, specialComments) {
  const children = new cssTree$1.List;
  let protectedComment, nonSpaceTokenInBuffer = !1;
  return input.nextUntil(input.head, ((node, item, list) => {
    if ("Comment" === node.type) return specialComments && "!" === node.value.charAt(0) ? !(!nonSpaceTokenInBuffer && !protectedComment) || (list.remove(item), 
    void (protectedComment = node)) : void list.remove(item);
    "WhiteSpace" !== node.type && (nonSpaceTokenInBuffer = !0), children.insert(list.remove(item));
  })), {
    comment: protectedComment,
    stylesheet: {
      type: "StyleSheet",
      loc: null,
      children
    }
  };
}

function compressChunk(ast, firstAtrulesAllowed, num, options) {
  options.logger(`Compress block #${num}`, null, !0);
  let seed = 1;
  return "StyleSheet" === ast.type && (ast.firstAtrulesAllowed = firstAtrulesAllowed, 
  ast.id = seed++), cssTree$1.walk(ast, {
    visit: "Atrule",
    enter(node) {
      null !== node.block && (node.block.id = seed++);
    }
  }), options.logger("init", ast), index(ast, options), options.logger("clean", ast), 
  index$1(ast), options.logger("replace", ast), options.restructuring && index$2(ast, options), 
  ast;
}

function getRestructureOption(options) {
  return "restructure" in options ? options.restructure : !("restructuring" in options) || options.restructuring;
}

const cssTree = cjs, compress$1 = function(ast, options) {
  ast = ast || {
    type: "StyleSheet",
    loc: null,
    children: new cssTree$1.List
  };
  const compressOptions = {
    logger: "function" == typeof (options = options || {}).logger ? options.logger : function() {},
    restructuring: getRestructureOption(options),
    forceMediaMerge: Boolean(options.forceMediaMerge),
    usage: !!options.usage && usage.buildIndex(options.usage)
  }, output = new cssTree$1.List;
  let input, chunk, chunkChildren, specialComments = function(options) {
    let comments = "comments" in options ? options.comments : "exclamation";
    return "boolean" == typeof comments ? comments = !!comments && "exclamation" : "exclamation" !== comments && "first-exclamation" !== comments && (comments = !1), 
    comments;
  }(options), firstAtrulesAllowed = !0, chunkNum = 1;
  var block;
  options.clone && (ast = cssTree$1.clone(ast)), "StyleSheet" === ast.type ? (input = ast.children, 
  ast.children = output) : (block = ast, input = (new cssTree$1.List).appendData({
    type: "Rule",
    loc: null,
    prelude: {
      type: "SelectorList",
      loc: null,
      children: (new cssTree$1.List).appendData({
        type: "Selector",
        loc: null,
        children: (new cssTree$1.List).appendData({
          type: "TypeSelector",
          loc: null,
          name: "x"
        })
      })
    },
    block
  }));
  do {
    if (chunk = readChunk(input, Boolean(specialComments)), compressChunk(chunk.stylesheet, firstAtrulesAllowed, chunkNum++, compressOptions), 
    chunkChildren = chunk.stylesheet.children, chunk.comment && (output.isEmpty || output.insert(cssTree$1.List.createItem({
      type: "Raw",
      value: "\n"
    })), output.insert(cssTree$1.List.createItem(chunk.comment)), chunkChildren.isEmpty || output.insert(cssTree$1.List.createItem({
      type: "Raw",
      value: "\n"
    }))), firstAtrulesAllowed && !chunkChildren.isEmpty) {
      const lastRule = chunkChildren.last;
      ("Atrule" !== lastRule.type || "import" !== lastRule.name && "charset" !== lastRule.name) && (firstAtrulesAllowed = !1);
    }
    "exclamation" !== specialComments && (specialComments = !1), output.appendList(chunkChildren);
  } while (!input.isEmpty);
  return {
    ast
  };
}, specificity$2 = specificity_1;

function encodeString(value) {
  const stringApostrophe = cssTree.string.encode(value, !0), stringQuote = cssTree.string.encode(value);
  return stringApostrophe.length < stringQuote.length ? stringApostrophe : stringQuote;
}

const {lexer, tokenize, parse: parse$1, generate: generate$1, walk, find, findLast, findAll, fromPlainObject, toPlainObject} = cssTree.fork({
  node: {
    String: {
      generate(node) {
        this.token(cssTree.tokenTypes.String, encodeString(node.value));
      }
    },
    Url: {
      generate(node) {
        const encodedUrl = cssTree.url.encode(node.value), string = encodeString(node.value);
        this.token(cssTree.tokenTypes.Url, encodedUrl.length <= string.length + 5 ? encodedUrl : "url(" + string + ")");
      }
    }
  }
});

syntax$2.compress = compress$1, syntax$2.specificity = specificity$2, syntax$2.find = find, 
syntax$2.findAll = findAll, syntax$2.findLast = findLast, syntax$2.fromPlainObject = fromPlainObject, 
syntax$2.generate = generate$1, syntax$2.lexer = lexer, syntax$2.parse = parse$1, 
syntax$2.toPlainObject = toPlainObject, syntax$2.tokenize = tokenize, syntax$2.walk = walk;

var utils$2 = {};

const processSelector = processSelector_1, utils$1 = utils$6;

utils$2.processSelector = processSelector, utils$2.addSelectors = utils$1.addSelectors, 
utils$2.compareDeclarations = utils$1.compareDeclarations, utils$2.hasSimilarSelectors = utils$1.hasSimilarSelectors, 
utils$2.isEqualDeclarations = utils$1.isEqualDeclarations, utils$2.isEqualSelectors = utils$1.isEqualSelectors, 
utils$2.unsafeToSkipNode = utils$1.unsafeToSkipNode;

const syntax = syntax$2, utils = utils$2, {parse, generate, compress} = syntax;

function debugOutput(name, options, startTime, data) {
  return options.debug && console.error(`## ${name} done in %d ms\n`, Date.now() - startTime), 
  data;
}

function buildCompressOptions(options) {
  return "function" != typeof (options = {
    ...options
  }).logger && options.debug && (options.logger = function(level) {
    let lastDebug;
    return function(title, ast) {
      let line = title;
      if (ast && (line = `[${((Date.now() - lastDebug) / 1000).toFixed(3)}s] ${line}`), 
      level > 1 && ast) {
        let css = generate(ast);
        2 === level && css.length > 256 && (css = css.substr(0, 256) + "..."), line += `\n  ${css}\n`;
      }
      console.error(line), lastDebug = Date.now();
    };
  }(options.debug)), options;
}

function runHandler(ast, options, handlers) {
  Array.isArray(handlers) || (handlers = [ handlers ]), handlers.forEach((fn => fn(ast, options)));
}

function minify(context, source, options) {
  const filename = (options = options || {}).filename || "<unknown>";
  let result;
  const ast = debugOutput("parsing", options, Date.now(), parse(source, {
    context,
    filename,
    positions: Boolean(options.sourceMap)
  }));
  options.beforeCompress && debugOutput("beforeCompress", options, Date.now(), runHandler(ast, options, options.beforeCompress));
  const compressResult = debugOutput("compress", options, Date.now(), compress(ast, buildCompressOptions(options)));
  return options.afterCompress && debugOutput("afterCompress", options, Date.now(), runHandler(compressResult, options, options.afterCompress)), 
  result = options.sourceMap ? debugOutput("generate(sourceMap: true)", options, Date.now(), (() => {
    const tmp = generate(compressResult.ast, {
      sourceMap: !0
    });
    return tmp.map._file = filename, tmp.map.setSourceContent(filename, source), tmp;
  })()) : debugOutput("generate", options, Date.now(), {
    css: generate(compressResult.ast),
    map: null
  }), result;
}

cjs$1.version = "5.0.5".version, cjs$1.syntax = syntax, cjs$1.utils = utils, cjs$1.minify = function(source, options) {
  return minify("stylesheet", source, options);
}, cjs$1.minifyBlock = function(source, options) {
  return minify("declarationList", source, options);
};

const csstree$2 = cjs$2, {syntax: {specificity: specificity$1}} = cjs$1, {visitSkip: visitSkip$5, querySelectorAll: querySelectorAll$1, detachNodeFromParent: detachNodeFromParent$f} = xast;

inlineStyles$2.name = "inlineStyles", inlineStyles$2.description = "inline styles (additional options)";

inlineStyles$2.fn = (root, params) => {
  const {onlyMatchedOnce = !0, removeMatchedSelectors = !0, useMqs = [ "", "screen" ], usePseudos = [ "" ]} = params, styles = [];
  let selectors = [];
  return {
    element: {
      enter: (node, parentNode) => {
        if ("foreignObject" === node.name) return visitSkip$5;
        if ("style" !== node.name || 0 === node.children.length) return;
        if (null != node.attributes.type && "" !== node.attributes.type && "text/css" !== node.attributes.type) return;
        let cssText = "";
        for (const child of node.children) "text" !== child.type && "cdata" !== child.type || (cssText += child.value);
        let cssAst = null;
        try {
          cssAst = csstree$2.parse(cssText, {
            parseValue: !1,
            parseCustomProperty: !1
          });
        } catch {
          return;
        }
        "StyleSheet" === cssAst.type && styles.push({
          node,
          parentNode,
          cssAst
        }), csstree$2.walk(cssAst, {
          visit: "Selector",
          enter(node, item) {
            const atrule = this.atrule, rule = this.rule;
            if (null == rule) return;
            let mq = "";
            if (null != atrule && (mq = atrule.name, null != atrule.prelude && (mq += ` ${csstree$2.generate(atrule.prelude)}`)), 
            !1 === useMqs.includes(mq)) return;
            const pseudos = [];
            "Selector" === node.type && node.children.forEach(((childNode, childItem, childList) => {
              "PseudoClassSelector" !== childNode.type && "PseudoElementSelector" !== childNode.type || pseudos.push({
                item: childItem,
                list: childList
              });
            }));
            const pseudoSelectors = csstree$2.generate({
              type: "Selector",
              children: (new csstree$2.List).fromArray(pseudos.map((pseudo => pseudo.item.data)))
            });
            if (!1 !== usePseudos.includes(pseudoSelectors)) {
              for (const pseudo of pseudos) pseudo.list.remove(pseudo.item);
              selectors.push({
                node,
                item,
                rule
              });
            }
          }
        });
      }
    },
    root: {
      exit: () => {
        if (0 === styles.length) return;
        const sortedSelectors = [ ...selectors ].sort(((a, b) => ((a, b) => {
          for (var i = 0; i < 4; i += 1) {
            if (a[i] < b[i]) return -1;
            if (a[i] > b[i]) return 1;
          }
          return 0;
        })(specificity$1(a.item.data), specificity$1(b.item.data)))).reverse();
        for (const selector of sortedSelectors) {
          const selectorText = csstree$2.generate(selector.item.data), matchedElements = [];
          try {
            for (const node of querySelectorAll$1(root, selectorText)) "element" === node.type && matchedElements.push(node);
          } catch (selectError) {
            continue;
          }
          if (0 !== matchedElements.length && !(onlyMatchedOnce && matchedElements.length > 1)) {
            for (const selectedEl of matchedElements) {
              const styleDeclarationList = csstree$2.parse(null == selectedEl.attributes.style ? "" : selectedEl.attributes.style, {
                context: "declarationList",
                parseValue: !1
              });
              if ("DeclarationList" !== styleDeclarationList.type) continue;
              const styleDeclarationItems = new Map;
              csstree$2.walk(styleDeclarationList, {
                visit: "Declaration",
                enter(node, item) {
                  styleDeclarationItems.set(node.property, item);
                }
              }), csstree$2.walk(selector.rule, {
                visit: "Declaration",
                enter(ruleDeclaration) {
                  const matchedItem = styleDeclarationItems.get(ruleDeclaration.property), ruleDeclarationItem = styleDeclarationList.children.createItem(ruleDeclaration);
                  null == matchedItem ? styleDeclarationList.children.append(ruleDeclarationItem) : !0 !== matchedItem.data.important && !0 === ruleDeclaration.important && (styleDeclarationList.children.replace(matchedItem, ruleDeclarationItem), 
                  styleDeclarationItems.set(ruleDeclaration.property, ruleDeclarationItem));
                }
              }), selectedEl.attributes.style = csstree$2.generate(styleDeclarationList);
            }
            removeMatchedSelectors && 0 !== matchedElements.length && "SelectorList" === selector.rule.prelude.type && selector.rule.prelude.children.remove(selector.item), 
            selector.matchedElements = matchedElements;
          }
        }
        if (!1 !== removeMatchedSelectors) {
          for (const selector of sortedSelectors) if (null != selector.matchedElements && !(onlyMatchedOnce && selector.matchedElements.length > 1)) for (const selectedEl of selector.matchedElements) {
            const classList = new Set(null == selectedEl.attributes.class ? null : selectedEl.attributes.class.split(" ")), firstSubSelector = selector.node.children.first;
            null != firstSubSelector && "ClassSelector" === firstSubSelector.type && classList.delete(firstSubSelector.name), 
            0 === classList.size ? delete selectedEl.attributes.class : selectedEl.attributes.class = Array.from(classList).join(" "), 
            null != firstSubSelector && "IdSelector" === firstSubSelector.type && selectedEl.attributes.id === firstSubSelector.name && delete selectedEl.attributes.id;
          }
          for (const style of styles) if (csstree$2.walk(style.cssAst, {
            visit: "Rule",
            enter: function(node, item, list) {
              "Rule" === node.type && "SelectorList" === node.prelude.type && node.prelude.children.isEmpty && list.remove(item);
            }
          }), style.cssAst.children.isEmpty) detachNodeFromParent$f(style.node, style.parentNode); else {
            const firstChild = style.node.children[0];
            "text" !== firstChild.type && "cdata" !== firstChild.type || (firstChild.value = csstree$2.generate(style.cssAst));
          }
        }
      }
    }
  };
};

var minifyStyles$1 = {};

const csso = cjs$1;

minifyStyles$1.name = "minifyStyles", minifyStyles$1.description = "minifies styles and removes unused styles based on usage data", 
minifyStyles$1.fn = (_root, {usage, ...params}) => {
  let enableTagsUsage = !0, enableIdsUsage = !0, enableClassesUsage = !0, forceUsageDeoptimized = !1;
  "boolean" == typeof usage ? (enableTagsUsage = usage, enableIdsUsage = usage, enableClassesUsage = usage) : usage && (enableTagsUsage = null == usage.tags || usage.tags, 
  enableIdsUsage = null == usage.ids || usage.ids, enableClassesUsage = null == usage.classes || usage.classes, 
  forceUsageDeoptimized = null != usage.force && usage.force);
  const styleElements = [], elementsWithStyleAttributes = [];
  let deoptimized = !1;
  const tagsUsage = new Set, idsUsage = new Set, classesUsage = new Set;
  return {
    element: {
      enter: node => {
        "script" === node.name && (deoptimized = !0);
        for (const name of Object.keys(node.attributes)) name.startsWith("on") && (deoptimized = !0);
        if (tagsUsage.add(node.name), null != node.attributes.id && idsUsage.add(node.attributes.id), 
        null != node.attributes.class) for (const className of node.attributes.class.split(/\s+/)) classesUsage.add(className);
        "style" === node.name && 0 !== node.children.length ? styleElements.push(node) : null != node.attributes.style && elementsWithStyleAttributes.push(node);
      }
    },
    root: {
      exit: () => {
        const cssoUsage = {};
        !1 !== deoptimized && !0 !== forceUsageDeoptimized || (enableTagsUsage && 0 !== tagsUsage.size && (cssoUsage.tags = Array.from(tagsUsage)), 
        enableIdsUsage && 0 !== idsUsage.size && (cssoUsage.ids = Array.from(idsUsage)), 
        enableClassesUsage && 0 !== classesUsage.size && (cssoUsage.classes = Array.from(classesUsage)));
        for (const node of styleElements) if ("text" === node.children[0].type || "cdata" === node.children[0].type) {
          const cssText = node.children[0].value, minified = csso.minify(cssText, {
            ...params,
            usage: cssoUsage
          }).css;
          cssText.indexOf(">") >= 0 || cssText.indexOf("<") >= 0 ? (node.children[0].type = "cdata", 
          node.children[0].value = minified) : (node.children[0].type = "text", node.children[0].value = minified);
        }
        for (const node of elementsWithStyleAttributes) {
          const elemStyle = node.attributes.style;
          node.attributes.style = csso.minifyBlock(elemStyle, {
            ...params
          }).css;
        }
      }
    }
  };
};

var cleanupIds$2 = {};

const {visitSkip: visitSkip$4} = xast, {referencesProps: referencesProps$3} = _collections;

cleanupIds$2.name = "cleanupIds", cleanupIds$2.description = "removes unused IDs and minifies used";

const regReferencesUrl = /\burl\((["'])?#(.+?)\1\)/, regReferencesHref = /^#(.+?)$/, regReferencesBegin = /(\D+)\./, generateIdChars = [ "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z" ], maxIdIndex = generateIdChars.length - 1, generateId = currentId => {
  if (null == currentId) return [ 0 ];
  currentId[currentId.length - 1] += 1;
  for (let i = currentId.length - 1; i > 0; i--) currentId[i] > maxIdIndex && (currentId[i] = 0, 
  void 0 !== currentId[i - 1] && currentId[i - 1]++);
  return currentId[0] > maxIdIndex && (currentId[0] = 0, currentId.unshift(0)), currentId;
};

cleanupIds$2.fn = (_root, params) => {
  const {remove = !0, minify = !0, preserve = [], preservePrefixes = [], force = !1} = params, preserveIds = new Set(Array.isArray(preserve) ? preserve : preserve ? [ preserve ] : []), preserveIdPrefixes = Array.isArray(preservePrefixes) ? preservePrefixes : preservePrefixes ? [ preservePrefixes ] : [], nodeById = new Map, referencesById = new Map;
  let deoptimized = !1;
  return {
    element: {
      enter: node => {
        if (0 == force) {
          if (("style" === node.name || "script" === node.name) && 0 !== node.children.length) return void (deoptimized = !0);
          if ("svg" === node.name) {
            let hasDefsOnly = !0;
            for (const child of node.children) if ("element" !== child.type || "defs" !== child.name) {
              hasDefsOnly = !1;
              break;
            }
            if (hasDefsOnly) return visitSkip$4;
          }
        }
        for (const [name, value] of Object.entries(node.attributes)) if ("id" === name) {
          const id = value;
          nodeById.has(id) ? delete node.attributes.id : nodeById.set(id, node);
        } else {
          let id = null;
          if (referencesProps$3.includes(name)) {
            const match = value.match(regReferencesUrl);
            null != match && (id = match[2]);
          }
          if ("href" === name || name.endsWith(":href")) {
            const match = value.match(regReferencesHref);
            null != match && (id = match[1]);
          }
          if ("begin" === name) {
            const match = value.match(regReferencesBegin);
            null != match && (id = match[1]);
          }
          if (null != id) {
            let refs = referencesById.get(id);
            null == refs && (refs = [], referencesById.set(id, refs)), refs.push({
              element: node,
              name,
              value
            });
          }
        }
      }
    },
    root: {
      exit: () => {
        if (deoptimized) return;
        const isIdPreserved = id => preserveIds.has(id) || ((string, prefixes) => {
          for (const prefix of prefixes) if (string.startsWith(prefix)) return !0;
          return !1;
        })(id, preserveIdPrefixes);
        let currentId = null;
        for (const [id, refs] of referencesById) {
          const node = nodeById.get(id);
          if (null != node) {
            if (minify && !1 === isIdPreserved(id)) {
              let currentIdString = null;
              do {
                currentId = generateId(currentId), currentIdString = currentId.map((i => generateIdChars[i])).join("");
              } while (isIdPreserved(currentIdString));
              node.attributes.id = currentIdString;
              for (const {element, name, value} of refs) value.includes("#") ? element.attributes[name] = value.replace(`#${id}`, `#${currentIdString}`) : element.attributes[name] = value.replace(`${id}.`, `${currentIdString}.`);
            }
            nodeById.delete(id);
          }
        }
        if (remove) for (const [id, node] of nodeById) !1 === isIdPreserved(id) && delete node.attributes.id;
      }
    }
  };
};

var removeUselessDefs$2 = {};

const {detachNodeFromParent: detachNodeFromParent$e} = xast, {elemsGroups: elemsGroups$4} = _collections;

removeUselessDefs$2.name = "removeUselessDefs", removeUselessDefs$2.description = "removes elements in <defs> without id", 
removeUselessDefs$2.fn = () => ({
  element: {
    enter: (node, parentNode) => {
      if ("defs" === node.name) {
        const usefulNodes = [];
        collectUsefulNodes(node, usefulNodes), 0 === usefulNodes.length && detachNodeFromParent$e(node, parentNode);
        for (const usefulNode of usefulNodes) Object.defineProperty(usefulNode, "parentNode", {
          writable: !0,
          value: node
        });
        node.children = usefulNodes;
      } else elemsGroups$4.nonRendering.includes(node.name) && null == node.attributes.id && detachNodeFromParent$e(node, parentNode);
    }
  }
});

const collectUsefulNodes = (node, usefulNodes) => {
  for (const child of node.children) "element" === child.type && (null != child.attributes.id || "style" === child.name ? usefulNodes.push(child) : collectUsefulNodes(child, usefulNodes));
};

var cleanupNumericValues$2 = {};

const {removeLeadingZero: removeLeadingZero$3} = tools;

cleanupNumericValues$2.name = "cleanupNumericValues", cleanupNumericValues$2.description = "rounds numeric values to the fixed precision, removes default ‘px’ units";

const regNumericValues$3 = /^([-+]?\d*\.?\d+([eE][-+]?\d+)?)(px|pt|pc|mm|cm|m|in|ft|em|ex|%)?$/, absoluteLengths$1 = {
  cm: 96 / 2.54,
  mm: 96 / 25.4,
  in: 96,
  pt: 4 / 3,
  pc: 16,
  px: 1
};

cleanupNumericValues$2.fn = (_root, params) => {
  const {floatPrecision = 3, leadingZero = !0, defaultPx = !0, convertToPx = !0} = params;
  return {
    element: {
      enter: node => {
        if (null != node.attributes.viewBox) {
          const nums = node.attributes.viewBox.split(/\s,?\s*|,\s*/g);
          node.attributes.viewBox = nums.map((value => {
            const num = Number(value);
            return Number.isNaN(num) ? value : Number(num.toFixed(floatPrecision));
          })).join(" ");
        }
        for (const [name, value] of Object.entries(node.attributes)) {
          if ("version" === name) continue;
          const match = value.match(regNumericValues$3);
          if (match) {
            let str, num = Number(Number(match[1]).toFixed(floatPrecision)), units = match[3] || "";
            if (convertToPx && "" !== units && units in absoluteLengths$1) {
              const pxNum = Number((absoluteLengths$1[units] * Number(match[1])).toFixed(floatPrecision));
              pxNum.toString().length < match[0].length && (num = pxNum, units = "px");
            }
            str = leadingZero ? removeLeadingZero$3(num) : num.toString(), defaultPx && "px" === units && (units = ""), 
            node.attributes[name] = str + units;
          }
        }
      }
    }
  };
};

var convertColors$2 = {};

const collections = _collections;

convertColors$2.name = "convertColors", convertColors$2.description = "converts colors: rgb() to #rrggbb and #rrggbb to #rgb";

const rNumber = "([+-]?(?:\\d*\\.\\d+|\\d+\\.?)%?)", regRGB = new RegExp("^rgb\\(\\s*" + rNumber + "\\s*,\\s*" + rNumber + "\\s*,\\s*" + rNumber + "\\s*\\)$"), regHEX = /^#(([a-fA-F0-9])\2){3}$/, convertRgbToHex = ([r, g, b]) => "#" + ((256 + r << 8 | g) << 8 | b).toString(16).slice(1).toUpperCase();

convertColors$2.fn = (_root, params) => {
  const {currentColor = !1, names2hex = !0, rgb2hex = !0, shorthex = !0, shortname = !0} = params;
  return {
    element: {
      enter: node => {
        for (const [name, value] of Object.entries(node.attributes)) if (collections.colorsProps.includes(name)) {
          let val = value;
          if (currentColor) {
            let matched;
            matched = "string" == typeof currentColor ? val === currentColor : currentColor instanceof RegExp ? null != currentColor.exec(val) : "none" !== val, 
            matched && (val = "currentColor");
          }
          if (names2hex) {
            const colorName = val.toLowerCase();
            null != collections.colorsNames[colorName] && (val = collections.colorsNames[colorName]);
          }
          if (rgb2hex) {
            let match = val.match(regRGB);
            if (null != match) {
              let nums = match.slice(1, 4).map((m => {
                let n;
                return n = m.indexOf("%") > -1 ? Math.round(2.55 * parseFloat(m)) : Number(m), Math.max(0, Math.min(n, 255));
              }));
              val = convertRgbToHex(nums);
            }
          }
          if (shorthex) {
            let match = val.match(regHEX);
            null != match && (val = "#" + match[0][1] + match[0][3] + match[0][5]);
          }
          if (shortname) {
            const colorName = val.toLowerCase();
            null != collections.colorsShortNames[colorName] && (val = collections.colorsShortNames[colorName]);
          }
          node.attributes[name] = val;
        }
      }
    }
  };
};

var removeUnknownsAndDefaults$2 = {}, style = {};

const csstree$1 = cjs$2, {syntax: {specificity}} = cjs$1, {visit: visit$5, matches} = xast, {attrsGroups: attrsGroups$4, inheritableAttrs: inheritableAttrs$3, presentationNonInheritableGroupAttrs: presentationNonInheritableGroupAttrs$2} = _collections, csstreeWalkSkip = csstree$1.walk.skip, parseRule = (ruleNode, dynamic) => {
  const declarations = [];
  ruleNode.block.children.forEach((cssNode => {
    "Declaration" === cssNode.type && declarations.push({
      name: cssNode.property,
      value: csstree$1.generate(cssNode.value),
      important: !0 === cssNode.important
    });
  }));
  const rules = [];
  return csstree$1.walk(ruleNode.prelude, (node => {
    if ("Selector" === node.type) {
      const newNode = csstree$1.clone(node);
      let hasPseudoClasses = !1;
      csstree$1.walk(newNode, ((pseudoClassNode, item, list) => {
        "PseudoClassSelector" === pseudoClassNode.type && (hasPseudoClasses = !0, list.remove(item));
      })), rules.push({
        specificity: specificity(node),
        dynamic: hasPseudoClasses || dynamic,
        selector: csstree$1.generate(newNode),
        declarations
      });
    }
  })), rules;
}, parseStylesheet = (css, dynamic) => {
  const rules = [], ast = csstree$1.parse(css, {
    parseValue: !1,
    parseAtrulePrelude: !1
  });
  return csstree$1.walk(ast, (cssNode => "Rule" === cssNode.type ? (rules.push(...parseRule(cssNode, dynamic || !1)), 
  csstreeWalkSkip) : "Atrule" === cssNode.type ? ("keyframes" === cssNode.name || csstree$1.walk(cssNode, (ruleNode => {
    if ("Rule" === ruleNode.type) return rules.push(...parseRule(ruleNode, dynamic || !0)), 
    csstreeWalkSkip;
  })), csstreeWalkSkip) : void 0)), rules;
}, computeOwnStyle = (stylesheet, node) => {
  const computedStyle = {}, importantStyles = new Map;
  for (const [name, value] of Object.entries(node.attributes)) attrsGroups$4.presentation.includes(name) && (computedStyle[name] = {
    type: "static",
    inherited: !1,
    value
  }, importantStyles.set(name, !1));
  for (const {selector, declarations, dynamic} of stylesheet.rules) if (matches(node, selector)) for (const {name, value, important} of declarations) {
    const computed = computedStyle[name];
    computed && "dynamic" === computed.type || (dynamic ? computedStyle[name] = {
      type: "dynamic",
      inherited: !1
    } : null != computed && !0 !== important && !1 !== importantStyles.get(name) || (computedStyle[name] = {
      type: "static",
      inherited: !1,
      value
    }, importantStyles.set(name, important)));
  }
  const styleDeclarations = null == node.attributes.style ? [] : (css => {
    const declarations = [], ast = csstree$1.parse(css, {
      context: "declarationList",
      parseValue: !1
    });
    return csstree$1.walk(ast, (cssNode => {
      "Declaration" === cssNode.type && declarations.push({
        name: cssNode.property,
        value: csstree$1.generate(cssNode.value),
        important: !0 === cssNode.important
      });
    })), declarations;
  })(node.attributes.style);
  for (const {name, value, important} of styleDeclarations) {
    const computed = computedStyle[name];
    computed && "dynamic" === computed.type || (null != computed && !0 !== important && !1 !== importantStyles.get(name) || (computedStyle[name] = {
      type: "static",
      inherited: !1,
      value
    }, importantStyles.set(name, important)));
  }
  return computedStyle;
};

style.collectStylesheet = root => {
  const rules = [], parents = new Map;
  return visit$5(root, {
    element: {
      enter: (node, parentNode) => {
        if (parents.set(node, parentNode), "style" === node.name) {
          const dynamic = null != node.attributes.media && "all" !== node.attributes.media;
          if (null == node.attributes.type || "" === node.attributes.type || "text/css" === node.attributes.type) {
            const children = node.children;
            for (const child of children) "text" !== child.type && "cdata" !== child.type || rules.push(...parseStylesheet(child.value, dynamic));
          }
        }
      }
    }
  }), rules.sort(((a, b) => ((a, b) => {
    for (let i = 0; i < 4; i += 1) {
      if (a[i] < b[i]) return -1;
      if (a[i] > b[i]) return 1;
    }
    return 0;
  })(a.specificity, b.specificity))), {
    rules,
    parents
  };
};

style.computeStyle = (stylesheet, node) => {
  const {parents} = stylesheet, computedStyles = computeOwnStyle(stylesheet, node);
  let parent = parents.get(node);
  for (;null != parent && "root" !== parent.type; ) {
    const inheritedStyles = computeOwnStyle(stylesheet, parent);
    for (const [name, computed] of Object.entries(inheritedStyles)) null == computedStyles[name] && !0 === inheritableAttrs$3.includes(name) && !1 === presentationNonInheritableGroupAttrs$2.includes(name) && (computedStyles[name] = {
      ...computed,
      inherited: !0
    });
    parent = parents.get(parent);
  }
  return computedStyles;
};

const {visitSkip: visitSkip$3, detachNodeFromParent: detachNodeFromParent$d} = xast, {collectStylesheet: collectStylesheet$5, computeStyle: computeStyle$5} = style, {elems, attrsGroups: attrsGroups$3, elemsGroups: elemsGroups$3, attrsGroupsDefaults: attrsGroupsDefaults$1, presentationNonInheritableGroupAttrs: presentationNonInheritableGroupAttrs$1} = _collections;

removeUnknownsAndDefaults$2.name = "removeUnknownsAndDefaults", removeUnknownsAndDefaults$2.description = "removes unknown elements content and attributes, removes attrs with default values";

const allowedChildrenPerElement = new Map, allowedAttributesPerElement = new Map, attributesDefaultsPerElement = new Map;

for (const [name, config] of Object.entries(elems)) {
  const allowedChildren = new Set;
  if (config.content) for (const elementName of config.content) allowedChildren.add(elementName);
  if (config.contentGroups) for (const contentGroupName of config.contentGroups) {
    const elemsGroup = elemsGroups$3[contentGroupName];
    if (elemsGroup) for (const elementName of elemsGroup) allowedChildren.add(elementName);
  }
  const allowedAttributes = new Set;
  if (config.attrs) for (const attrName of config.attrs) allowedAttributes.add(attrName);
  const attributesDefaults = new Map;
  if (config.defaults) for (const [attrName, defaultValue] of Object.entries(config.defaults)) attributesDefaults.set(attrName, defaultValue);
  for (const attrsGroupName of config.attrsGroups) {
    const attrsGroup = attrsGroups$3[attrsGroupName];
    if (attrsGroup) for (const attrName of attrsGroup) allowedAttributes.add(attrName);
    const groupDefaults = attrsGroupsDefaults$1[attrsGroupName];
    if (groupDefaults) for (const [attrName, defaultValue] of Object.entries(groupDefaults)) attributesDefaults.set(attrName, defaultValue);
  }
  allowedChildrenPerElement.set(name, allowedChildren), allowedAttributesPerElement.set(name, allowedAttributes), 
  attributesDefaultsPerElement.set(name, attributesDefaults);
}

removeUnknownsAndDefaults$2.fn = (root, params) => {
  const {unknownContent = !0, unknownAttrs = !0, defaultAttrs = !0, uselessOverrides = !0, keepDataAttrs = !0, keepAriaAttrs = !0, keepRoleAttr = !1} = params, stylesheet = collectStylesheet$5(root);
  return {
    element: {
      enter: (node, parentNode) => {
        if (node.name.includes(":")) return;
        if ("foreignObject" === node.name) return visitSkip$3;
        if (unknownContent && "element" === parentNode.type) {
          const allowedChildren = allowedChildrenPerElement.get(parentNode.name);
          if (null == allowedChildren || 0 === allowedChildren.size) {
            if (null == allowedChildrenPerElement.get(node.name)) return void detachNodeFromParent$d(node, parentNode);
          } else if (!1 === allowedChildren.has(node.name)) return void detachNodeFromParent$d(node, parentNode);
        }
        const allowedAttributes = allowedAttributesPerElement.get(node.name), attributesDefaults = attributesDefaultsPerElement.get(node.name), computedParentStyle = "element" === parentNode.type ? computeStyle$5(stylesheet, parentNode) : null;
        for (const [name, value] of Object.entries(node.attributes)) if (!(keepDataAttrs && name.startsWith("data-") || keepAriaAttrs && name.startsWith("aria-") || keepRoleAttr && "role" === name || "xmlns" === name)) {
          if (name.includes(":")) {
            const [prefix] = name.split(":");
            if ("xml" !== prefix && "xlink" !== prefix) continue;
          }
          if (unknownAttrs && allowedAttributes && !1 === allowedAttributes.has(name) && delete node.attributes[name], 
          defaultAttrs && null == node.attributes.id && attributesDefaults && attributesDefaults.get(name) === value && (null != computedParentStyle && null != computedParentStyle[name] || delete node.attributes[name]), 
          uselessOverrides && null == node.attributes.id) {
            const style = null == computedParentStyle ? null : computedParentStyle[name];
            !1 === presentationNonInheritableGroupAttrs$1.includes(name) && null != style && "static" === style.type && style.value === value && delete node.attributes[name];
          }
        }
      }
    }
  };
};

var removeNonInheritableGroupAttrs$2 = {};

const {inheritableAttrs: inheritableAttrs$2, attrsGroups: attrsGroups$2, presentationNonInheritableGroupAttrs} = _collections;

removeNonInheritableGroupAttrs$2.name = "removeNonInheritableGroupAttrs", removeNonInheritableGroupAttrs$2.description = "removes non-inheritable group’s presentational attributes", 
removeNonInheritableGroupAttrs$2.fn = () => ({
  element: {
    enter: node => {
      if ("g" === node.name) for (const name of Object.keys(node.attributes)) !0 === attrsGroups$2.presentation.includes(name) && !1 === inheritableAttrs$2.includes(name) && !1 === presentationNonInheritableGroupAttrs.includes(name) && delete node.attributes[name];
    }
  }
});

var removeUselessStrokeAndFill$2 = {};

const {visit: visit$4, visitSkip: visitSkip$2, detachNodeFromParent: detachNodeFromParent$c} = xast, {collectStylesheet: collectStylesheet$4, computeStyle: computeStyle$4} = style, {elemsGroups: elemsGroups$2} = _collections;

removeUselessStrokeAndFill$2.name = "removeUselessStrokeAndFill", removeUselessStrokeAndFill$2.description = "removes useless stroke and fill attributes", 
removeUselessStrokeAndFill$2.fn = (root, params) => {
  const {stroke: removeStroke = !0, fill: removeFill = !0, removeNone = !1} = params;
  let hasStyleOrScript = !1;
  if (visit$4(root, {
    element: {
      enter: node => {
        "style" !== node.name && "script" !== node.name || (hasStyleOrScript = !0);
      }
    }
  }), hasStyleOrScript) return null;
  const stylesheet = collectStylesheet$4(root);
  return {
    element: {
      enter: (node, parentNode) => {
        if (null != node.attributes.id) return visitSkip$2;
        if (0 == elemsGroups$2.shape.includes(node.name)) return;
        const computedStyle = computeStyle$4(stylesheet, node), stroke = computedStyle.stroke, strokeOpacity = computedStyle["stroke-opacity"], strokeWidth = computedStyle["stroke-width"], markerEnd = computedStyle["marker-end"], fill = computedStyle.fill, fillOpacity = computedStyle["fill-opacity"], computedParentStyle = "element" === parentNode.type ? computeStyle$4(stylesheet, parentNode) : null, parentStroke = null == computedParentStyle ? null : computedParentStyle.stroke;
        if (removeStroke && (null == stroke || "static" === stroke.type && "none" == stroke.value || null != strokeOpacity && "static" === strokeOpacity.type && "0" === strokeOpacity.value || null != strokeWidth && "static" === strokeWidth.type && "0" === strokeWidth.value) && (null != strokeWidth && "static" === strokeWidth.type && "0" === strokeWidth.value || null == markerEnd)) {
          for (const name of Object.keys(node.attributes)) name.startsWith("stroke") && delete node.attributes[name];
          null != parentStroke && "static" === parentStroke.type && "none" !== parentStroke.value && (node.attributes.stroke = "none");
        }
        if (removeFill && (null != fill && "static" === fill.type && "none" === fill.value || null != fillOpacity && "static" === fillOpacity.type && "0" === fillOpacity.value)) {
          for (const name of Object.keys(node.attributes)) name.startsWith("fill-") && delete node.attributes[name];
          (null == fill || "static" === fill.type && "none" !== fill.value) && (node.attributes.fill = "none");
        }
        removeNone && (null != stroke && "none" !== node.attributes.stroke || (null == fill || "static" !== fill.type || "none" !== fill.value) && "none" !== node.attributes.fill || detachNodeFromParent$c(node, parentNode));
      }
    }
  };
};

var removeViewBox$2 = {
  name: "removeViewBox",
  description: "removes viewBox attribute when possible"
};

const viewBoxElems = [ "svg", "pattern", "symbol" ];

removeViewBox$2.fn = () => ({
  element: {
    enter: (node, parentNode) => {
      if (viewBoxElems.includes(node.name) && null != node.attributes.viewBox && null != node.attributes.width && null != node.attributes.height) {
        if ("svg" === node.name && "root" !== parentNode.type) return;
        const nums = node.attributes.viewBox.split(/[ ,]+/g);
        "0" === nums[0] && "0" === nums[1] && node.attributes.width.replace(/px$/, "") === nums[2] && node.attributes.height.replace(/px$/, "") === nums[3] && delete node.attributes.viewBox;
      }
    }
  }
});

var cleanupEnableBackground$2 = {};

const {visit: visit$3} = xast;

cleanupEnableBackground$2.name = "cleanupEnableBackground", cleanupEnableBackground$2.description = "remove or cleanup enable-background attribute when possible", 
cleanupEnableBackground$2.fn = root => {
  const regEnableBackground = /^new\s0\s0\s([-+]?\d*\.?\d+([eE][-+]?\d+)?)\s([-+]?\d*\.?\d+([eE][-+]?\d+)?)$/;
  let hasFilter = !1;
  return visit$3(root, {
    element: {
      enter: node => {
        "filter" === node.name && (hasFilter = !0);
      }
    }
  }), {
    element: {
      enter: node => {
        if (null != node.attributes["enable-background"]) if (hasFilter) {
          if (("svg" === node.name || "mask" === node.name || "pattern" === node.name) && null != node.attributes.width && null != node.attributes.height) {
            const match = node.attributes["enable-background"].match(regEnableBackground);
            null != match && node.attributes.width === match[1] && node.attributes.height === match[3] && ("svg" === node.name ? delete node.attributes["enable-background"] : node.attributes["enable-background"] = "new");
          }
        } else delete node.attributes["enable-background"];
      }
    }
  };
};

var removeHiddenElems$2 = {}, path = {};

const {removeLeadingZero: removeLeadingZero$2} = tools, argsCountPerCommand = {
  M: 2,
  m: 2,
  Z: 0,
  z: 0,
  L: 2,
  l: 2,
  H: 1,
  h: 1,
  V: 1,
  v: 1,
  C: 6,
  c: 6,
  S: 4,
  s: 4,
  Q: 4,
  q: 4,
  T: 2,
  t: 2,
  A: 7,
  a: 7
}, isCommand = c => c in argsCountPerCommand, isWsp = c => {
  const codePoint = c.codePointAt(0);
  return 0x20 === codePoint || 0x9 === codePoint || 0xd === codePoint || 0xa === codePoint;
}, isDigit = c => {
  const codePoint = c.codePointAt(0);
  return null != codePoint && (48 <= codePoint && codePoint <= 57);
}, readNumber = (string, cursor) => {
  let i = cursor, value = "", state = "none";
  for (;i < string.length; i += 1) {
    const c = string[i];
    if ("+" === c || "-" === c) {
      if ("none" === state) {
        state = "sign", value += c;
        continue;
      }
      if ("e" === state) {
        state = "exponent_sign", value += c;
        continue;
      }
    }
    if (isDigit(c)) {
      if ("none" === state || "sign" === state || "whole" === state) {
        state = "whole", value += c;
        continue;
      }
      if ("decimal_point" === state || "decimal" === state) {
        state = "decimal", value += c;
        continue;
      }
      if ("e" === state || "exponent_sign" === state || "exponent" === state) {
        state = "exponent", value += c;
        continue;
      }
    }
    if ("." !== c || "none" !== state && "sign" !== state && "whole" !== state) {
      if ("E" !== c && "e" != c || "whole" !== state && "decimal_point" !== state && "decimal" !== state) break;
      state = "e", value += c;
    } else state = "decimal_point", value += c;
  }
  const number = Number.parseFloat(value);
  return Number.isNaN(number) ? [ cursor, null ] : [ i - 1, number ];
};

path.parsePathData = string => {
  const pathData = [];
  let command = null, args = [], argsCount = 0, canHaveComma = !1, hadComma = !1;
  for (let i = 0; i < string.length; i += 1) {
    const c = string.charAt(i);
    if (isWsp(c)) continue;
    if (canHaveComma && "," === c) {
      if (hadComma) break;
      hadComma = !0;
      continue;
    }
    if (isCommand(c)) {
      if (hadComma) return pathData;
      if (null == command) {
        if ("M" !== c && "m" !== c) return pathData;
      } else if (0 !== args.length) return pathData;
      command = c, args = [], argsCount = argsCountPerCommand[command], canHaveComma = !1, 
      0 === argsCount && pathData.push({
        command,
        args
      });
      continue;
    }
    if (null == command) return pathData;
    let newCursor = i, number = null;
    if ("A" === command || "a" === command) {
      const position = args.length;
      0 !== position && 1 !== position || "+" !== c && "-" !== c && ([newCursor, number] = readNumber(string, i)), 
      2 !== position && 5 !== position && 6 !== position || ([newCursor, number] = readNumber(string, i)), 
      3 !== position && 4 !== position || ("0" === c && (number = 0), "1" === c && (number = 1));
    } else [newCursor, number] = readNumber(string, i);
    if (null == number) return pathData;
    args.push(number), canHaveComma = !0, hadComma = !1, i = newCursor, args.length === argsCount && (pathData.push({
      command,
      args
    }), "M" === command && (command = "L"), "m" === command && (command = "l"), args = []);
  }
  return pathData;
};

const stringifyNumber = (number, precision) => {
  if (null != precision) {
    const ratio = 10 ** precision;
    number = Math.round(number * ratio) / ratio;
  }
  return removeLeadingZero$2(number);
}, stringifyArgs = (command, args, precision, disableSpaceAfterFlags) => {
  let result = "", prev = "";
  for (let i = 0; i < args.length; i += 1) {
    const number = args[i], numberString = stringifyNumber(number, precision);
    !disableSpaceAfterFlags || "A" !== command && "a" !== command || i % 7 != 4 && i % 7 != 5 ? 0 === i || numberString.startsWith("-") || prev.includes(".") && numberString.startsWith(".") ? result += numberString : result += ` ${numberString}` : result += numberString, 
    prev = numberString;
  }
  return result;
};

path.stringifyPathData = ({pathData, precision, disableSpaceAfterFlags}) => {
  let combined = [];
  for (let i = 0; i < pathData.length; i += 1) {
    const {command, args} = pathData[i];
    if (0 === i) combined.push({
      command,
      args
    }); else {
      const last = combined[combined.length - 1];
      1 === i && ("L" === command && (last.command = "M"), "l" === command && (last.command = "m")), 
      last.command === command && "M" !== last.command && "m" !== last.command || "M" === last.command && "L" === command || "m" === last.command && "l" === command ? last.args = [ ...last.args, ...args ] : combined.push({
        command,
        args
      });
    }
  }
  let result = "";
  for (const {command, args} of combined) result += command + stringifyArgs(command, args, precision, disableSpaceAfterFlags);
  return result;
};

const {visit: visit$2, visitSkip: visitSkip$1, querySelector, detachNodeFromParent: detachNodeFromParent$b} = xast, {collectStylesheet: collectStylesheet$3, computeStyle: computeStyle$3} = style, {parsePathData: parsePathData$2} = path;

removeHiddenElems$2.name = "removeHiddenElems", removeHiddenElems$2.description = "removes hidden elements (zero sized, with absent attributes)", 
removeHiddenElems$2.fn = (root, params) => {
  const {isHidden = !0, displayNone = !0, opacity0 = !0, circleR0 = !0, ellipseRX0 = !0, ellipseRY0 = !0, rectWidth0 = !0, rectHeight0 = !0, patternWidth0 = !0, patternHeight0 = !0, imageWidth0 = !0, imageHeight0 = !0, pathEmptyD = !0, polylineEmptyPoints = !0, polygonEmptyPoints = !0} = params, stylesheet = collectStylesheet$3(root);
  return visit$2(root, {
    element: {
      enter: (node, parentNode) => {
        if ("clipPath" === node.name) return visitSkip$1;
        const computedStyle = computeStyle$3(stylesheet, node);
        opacity0 && computedStyle.opacity && "static" === computedStyle.opacity.type && "0" === computedStyle.opacity.value && detachNodeFromParent$b(node, parentNode);
      }
    }
  }), {
    element: {
      enter: (node, parentNode) => {
        const computedStyle = computeStyle$3(stylesheet, node);
        if (isHidden && computedStyle.visibility && "static" === computedStyle.visibility.type && "hidden" === computedStyle.visibility.value && null == querySelector(node, "[visibility=visible]")) detachNodeFromParent$b(node, parentNode); else if (displayNone && computedStyle.display && "static" === computedStyle.display.type && "none" === computedStyle.display.value && "marker" !== node.name) detachNodeFromParent$b(node, parentNode); else if (circleR0 && "circle" === node.name && 0 === node.children.length && "0" === node.attributes.r) detachNodeFromParent$b(node, parentNode); else if (ellipseRX0 && "ellipse" === node.name && 0 === node.children.length && "0" === node.attributes.rx) detachNodeFromParent$b(node, parentNode); else if (ellipseRY0 && "ellipse" === node.name && 0 === node.children.length && "0" === node.attributes.ry) detachNodeFromParent$b(node, parentNode); else if (rectWidth0 && "rect" === node.name && 0 === node.children.length && "0" === node.attributes.width) detachNodeFromParent$b(node, parentNode); else if (rectHeight0 && rectWidth0 && "rect" === node.name && 0 === node.children.length && "0" === node.attributes.height) detachNodeFromParent$b(node, parentNode); else if (patternWidth0 && "pattern" === node.name && "0" === node.attributes.width) detachNodeFromParent$b(node, parentNode); else if (patternHeight0 && "pattern" === node.name && "0" === node.attributes.height) detachNodeFromParent$b(node, parentNode); else if (imageWidth0 && "image" === node.name && "0" === node.attributes.width) detachNodeFromParent$b(node, parentNode); else if (imageHeight0 && "image" === node.name && "0" === node.attributes.height) detachNodeFromParent$b(node, parentNode); else {
          if (pathEmptyD && "path" === node.name) {
            if (null == node.attributes.d) return void detachNodeFromParent$b(node, parentNode);
            const pathData = parsePathData$2(node.attributes.d);
            return 0 === pathData.length || 1 === pathData.length && null == computedStyle["marker-start"] && null == computedStyle["marker-end"] ? void detachNodeFromParent$b(node, parentNode) : void 0;
          }
          (polylineEmptyPoints && "polyline" === node.name && null == node.attributes.points || polygonEmptyPoints && "polygon" === node.name && null == node.attributes.points) && detachNodeFromParent$b(node, parentNode);
        }
      }
    }
  };
};

var removeEmptyText$2 = {};

const {detachNodeFromParent: detachNodeFromParent$a} = xast;

removeEmptyText$2.name = "removeEmptyText", removeEmptyText$2.description = "removes empty <text> elements", 
removeEmptyText$2.fn = (root, params) => {
  const {text = !0, tspan = !0, tref = !0} = params;
  return {
    element: {
      enter: (node, parentNode) => {
        text && "text" === node.name && 0 === node.children.length && detachNodeFromParent$a(node, parentNode), 
        tspan && "tspan" === node.name && 0 === node.children.length && detachNodeFromParent$a(node, parentNode), 
        tref && "tref" === node.name && null == node.attributes["xlink:href"] && detachNodeFromParent$a(node, parentNode);
      }
    }
  };
};

var convertShapeToPath$1 = {};

const {stringifyPathData: stringifyPathData$1} = path, {detachNodeFromParent: detachNodeFromParent$9} = xast;

convertShapeToPath$1.name = "convertShapeToPath", convertShapeToPath$1.description = "converts basic shapes to more compact path form";

const regNumber = /[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?/g;

convertShapeToPath$1.fn = (root, params) => {
  const {convertArcs = !1, floatPrecision: precision} = params;
  return {
    element: {
      enter: (node, parentNode) => {
        if ("rect" === node.name && null != node.attributes.width && null != node.attributes.height && null == node.attributes.rx && null == node.attributes.ry) {
          const x = Number(node.attributes.x || "0"), y = Number(node.attributes.y || "0"), width = Number(node.attributes.width), height = Number(node.attributes.height);
          if (Number.isNaN(x - y + width - height)) return;
          const pathData = [ {
            command: "M",
            args: [ x, y ]
          }, {
            command: "H",
            args: [ x + width ]
          }, {
            command: "V",
            args: [ y + height ]
          }, {
            command: "H",
            args: [ x ]
          }, {
            command: "z",
            args: []
          } ];
          node.name = "path", node.attributes.d = stringifyPathData$1({
            pathData,
            precision
          }), delete node.attributes.x, delete node.attributes.y, delete node.attributes.width, 
          delete node.attributes.height;
        }
        if ("line" === node.name) {
          const x1 = Number(node.attributes.x1 || "0"), y1 = Number(node.attributes.y1 || "0"), x2 = Number(node.attributes.x2 || "0"), y2 = Number(node.attributes.y2 || "0");
          if (Number.isNaN(x1 - y1 + x2 - y2)) return;
          const pathData = [ {
            command: "M",
            args: [ x1, y1 ]
          }, {
            command: "L",
            args: [ x2, y2 ]
          } ];
          node.name = "path", node.attributes.d = stringifyPathData$1({
            pathData,
            precision
          }), delete node.attributes.x1, delete node.attributes.y1, delete node.attributes.x2, 
          delete node.attributes.y2;
        }
        if (("polyline" === node.name || "polygon" === node.name) && null != node.attributes.points) {
          const coords = (node.attributes.points.match(regNumber) || []).map(Number);
          if (coords.length < 4) return void detachNodeFromParent$9(node, parentNode);
          const pathData = [];
          for (let i = 0; i < coords.length; i += 2) pathData.push({
            command: 0 === i ? "M" : "L",
            args: coords.slice(i, i + 2)
          });
          "polygon" === node.name && pathData.push({
            command: "z",
            args: []
          }), node.name = "path", node.attributes.d = stringifyPathData$1({
            pathData,
            precision
          }), delete node.attributes.points;
        }
        if ("circle" === node.name && convertArcs) {
          const cx = Number(node.attributes.cx || "0"), cy = Number(node.attributes.cy || "0"), r = Number(node.attributes.r || "0");
          if (Number.isNaN(cx - cy + r)) return;
          const pathData = [ {
            command: "M",
            args: [ cx, cy - r ]
          }, {
            command: "A",
            args: [ r, r, 0, 1, 0, cx, cy + r ]
          }, {
            command: "A",
            args: [ r, r, 0, 1, 0, cx, cy - r ]
          }, {
            command: "z",
            args: []
          } ];
          node.name = "path", node.attributes.d = stringifyPathData$1({
            pathData,
            precision
          }), delete node.attributes.cx, delete node.attributes.cy, delete node.attributes.r;
        }
        if ("ellipse" === node.name && convertArcs) {
          const ecx = Number(node.attributes.cx || "0"), ecy = Number(node.attributes.cy || "0"), rx = Number(node.attributes.rx || "0"), ry = Number(node.attributes.ry || "0");
          if (Number.isNaN(ecx - ecy + rx - ry)) return;
          const pathData = [ {
            command: "M",
            args: [ ecx, ecy - ry ]
          }, {
            command: "A",
            args: [ rx, ry, 0, 1, 0, ecx, ecy + ry ]
          }, {
            command: "A",
            args: [ rx, ry, 0, 1, 0, ecx, ecy - ry ]
          }, {
            command: "z",
            args: []
          } ];
          node.name = "path", node.attributes.d = stringifyPathData$1({
            pathData,
            precision
          }), delete node.attributes.cx, delete node.attributes.cy, delete node.attributes.rx, 
          delete node.attributes.ry;
        }
      }
    }
  };
};

var convertEllipseToCircle$2 = {
  name: "convertEllipseToCircle",
  description: "converts non-eccentric <ellipse>s to <circle>s",
  fn: () => ({
    element: {
      enter: node => {
        if ("ellipse" === node.name) {
          const rx = node.attributes.rx || "0", ry = node.attributes.ry || "0";
          if (rx === ry || "auto" === rx || "auto" === ry) {
            node.name = "circle";
            const radius = "auto" === rx ? ry : rx;
            delete node.attributes.rx, delete node.attributes.ry, node.attributes.r = radius;
          }
        }
      }
    }
  })
}, moveElemsAttrsToGroup$1 = {};

const {visit: visit$1} = xast, {inheritableAttrs: inheritableAttrs$1, pathElems: pathElems$2} = _collections;

moveElemsAttrsToGroup$1.name = "moveElemsAttrsToGroup", moveElemsAttrsToGroup$1.description = "Move common attributes of group children to the group", 
moveElemsAttrsToGroup$1.fn = root => {
  let deoptimizedWithStyles = !1;
  return visit$1(root, {
    element: {
      enter: node => {
        "style" === node.name && (deoptimizedWithStyles = !0);
      }
    }
  }), {
    element: {
      exit: node => {
        if ("g" !== node.name || node.children.length <= 1) return;
        if (deoptimizedWithStyles) return;
        const commonAttributes = new Map;
        let initial = !0, everyChildIsPath = !0;
        for (const child of node.children) if ("element" === child.type) if (!1 === pathElems$2.includes(child.name) && (everyChildIsPath = !1), 
        initial) {
          initial = !1;
          for (const [name, value] of Object.entries(child.attributes)) inheritableAttrs$1.includes(name) && commonAttributes.set(name, value);
        } else for (const [name, value] of commonAttributes) child.attributes[name] !== value && commonAttributes.delete(name);
        null == node.attributes["clip-path"] && null == node.attributes.mask || commonAttributes.delete("transform"), 
        everyChildIsPath && commonAttributes.delete("transform");
        for (const [name, value] of commonAttributes) "transform" === name ? null != node.attributes.transform ? node.attributes.transform = `${node.attributes.transform} ${value}` : node.attributes.transform = value : node.attributes[name] = value;
        for (const child of node.children) if ("element" === child.type) for (const [name] of commonAttributes) delete child.attributes[name];
      }
    }
  };
};

var moveGroupAttrsToElems$1 = {};

const {pathElems: pathElems$1, referencesProps: referencesProps$2} = _collections;

moveGroupAttrsToElems$1.name = "moveGroupAttrsToElems", moveGroupAttrsToElems$1.description = "moves some group attributes to the content elements";

const pathElemsWithGroupsAndText = [ ...pathElems$1, "g", "text" ];

moveGroupAttrsToElems$1.fn = () => ({
  element: {
    enter: node => {
      if ("g" === node.name && 0 !== node.children.length && null != node.attributes.transform && !1 === Object.entries(node.attributes).some((([name, value]) => referencesProps$2.includes(name) && value.includes("url("))) && node.children.every((child => "element" === child.type && pathElemsWithGroupsAndText.includes(child.name) && null == child.attributes.id))) {
        for (const child of node.children) {
          const value = node.attributes.transform;
          "element" === child.type && (null != child.attributes.transform ? child.attributes.transform = `${value} ${child.attributes.transform}` : child.attributes.transform = value);
        }
        delete node.attributes.transform;
      }
    }
  }
});

var collapseGroups$2 = {};

const {inheritableAttrs, elemsGroups: elemsGroups$1} = _collections;

collapseGroups$2.name = "collapseGroups", collapseGroups$2.description = "collapses useless groups";

const hasAnimatedAttr = (node, name) => {
  if ("element" === node.type) {
    if (elemsGroups$1.animation.includes(node.name) && node.attributes.attributeName === name) return !0;
    for (const child of node.children) if (hasAnimatedAttr(child, name)) return !0;
  }
  return !1;
};

collapseGroups$2.fn = () => ({
  element: {
    exit: (node, parentNode) => {
      if ("root" !== parentNode.type && "switch" !== parentNode.name && "g" === node.name && 0 !== node.children.length) {
        if (0 !== Object.keys(node.attributes).length && 1 === node.children.length) {
          const firstChild = node.children[0];
          if ("element" === firstChild.type && null == firstChild.attributes.id && null == node.attributes.filter && (null == node.attributes.class || null == firstChild.attributes.class) && (null == node.attributes["clip-path"] && null == node.attributes.mask || "g" === firstChild.name && null == node.attributes.transform && null == firstChild.attributes.transform)) for (const [name, value] of Object.entries(node.attributes)) {
            if (hasAnimatedAttr(firstChild, name)) return;
            if (null == firstChild.attributes[name]) firstChild.attributes[name] = value; else if ("transform" === name) firstChild.attributes[name] = value + " " + firstChild.attributes[name]; else if ("inherit" === firstChild.attributes[name]) firstChild.attributes[name] = value; else if (!1 === inheritableAttrs.includes(name) && firstChild.attributes[name] !== value) return;
            delete node.attributes[name];
          }
        }
        if (0 === Object.keys(node.attributes).length) {
          for (const child of node.children) if ("element" === child.type && elemsGroups$1.animation.includes(child.name)) return;
          const index = parentNode.children.indexOf(node);
          parentNode.children.splice(index, 1, ...node.children);
          for (const child of node.children) Object.defineProperty(child, "parentNode", {
            writable: !0,
            value: parentNode
          });
        }
      }
    }
  }
});

var convertPathData$2 = {}, _path = {};

const {parsePathData: parsePathData$1, stringifyPathData} = path;

var prevCtrlPoint;

_path.path2js = path => {
  if (path.pathJS) return path.pathJS;
  const pathData = [], newPathData = parsePathData$1(path.attributes.d);
  for (const {command, args} of newPathData) pathData.push({
    command,
    args
  });
  return pathData.length && "m" == pathData[0].command && (pathData[0].command = "M"), 
  path.pathJS = pathData, pathData;
};

const convertRelativeToAbsolute = data => {
  const newData = [];
  let start = [ 0, 0 ], cursor = [ 0, 0 ];
  for (let {command, args} of data) args = args.slice(), "m" === command && (args[0] += cursor[0], 
  args[1] += cursor[1], command = "M"), "M" === command && (cursor[0] = args[0], cursor[1] = args[1], 
  start[0] = cursor[0], start[1] = cursor[1]), "h" === command && (args[0] += cursor[0], 
  command = "H"), "H" === command && (cursor[0] = args[0]), "v" === command && (args[0] += cursor[1], 
  command = "V"), "V" === command && (cursor[1] = args[0]), "l" === command && (args[0] += cursor[0], 
  args[1] += cursor[1], command = "L"), "L" === command && (cursor[0] = args[0], cursor[1] = args[1]), 
  "c" === command && (args[0] += cursor[0], args[1] += cursor[1], args[2] += cursor[0], 
  args[3] += cursor[1], args[4] += cursor[0], args[5] += cursor[1], command = "C"), 
  "C" === command && (cursor[0] = args[4], cursor[1] = args[5]), "s" === command && (args[0] += cursor[0], 
  args[1] += cursor[1], args[2] += cursor[0], args[3] += cursor[1], command = "S"), 
  "S" === command && (cursor[0] = args[2], cursor[1] = args[3]), "q" === command && (args[0] += cursor[0], 
  args[1] += cursor[1], args[2] += cursor[0], args[3] += cursor[1], command = "Q"), 
  "Q" === command && (cursor[0] = args[2], cursor[1] = args[3]), "t" === command && (args[0] += cursor[0], 
  args[1] += cursor[1], command = "T"), "T" === command && (cursor[0] = args[0], cursor[1] = args[1]), 
  "a" === command && (args[5] += cursor[0], args[6] += cursor[1], command = "A"), 
  "A" === command && (cursor[0] = args[5], cursor[1] = args[6]), "z" !== command && "Z" !== command || (cursor[0] = start[0], 
  cursor[1] = start[1], command = "z"), newData.push({
    command,
    args
  });
  return newData;
};

function set(dest, source) {
  return dest[0] = source[source.length - 2], dest[1] = source[source.length - 1], 
  dest;
}

function processSimplex(simplex, direction) {
  if (2 == simplex.length) {
    let a = simplex[1], b = simplex[0], AO = minus(simplex[1]), AB = sub(b, a);
    dot(AO, AB) > 0 ? set(direction, orth(AB, a)) : (set(direction, AO), simplex.shift());
  } else {
    let a = simplex[2], b = simplex[1], c = simplex[0], AB = sub(b, a), AC = sub(c, a), AO = minus(a), ACB = orth(AB, AC), ABC = orth(AC, AB);
    if (dot(ACB, AO) > 0) dot(AB, AO) > 0 ? (set(direction, ACB), simplex.shift()) : (set(direction, AO), 
    simplex.splice(0, 2)); else {
      if (!(dot(ABC, AO) > 0)) return !0;
      dot(AC, AO) > 0 ? (set(direction, ABC), simplex.splice(1, 1)) : (set(direction, AO), 
      simplex.splice(0, 2));
    }
  }
  return !1;
}

function minus(v) {
  return [ -v[0], -v[1] ];
}

function sub(v1, v2) {
  return [ v1[0] - v2[0], v1[1] - v2[1] ];
}

function dot(v1, v2) {
  return v1[0] * v2[0] + v1[1] * v2[1];
}

function orth(v, from) {
  var o = [ -v[1], v[0] ];
  return dot(o, minus(from)) < 0 ? minus(o) : o;
}

function gatherPoints(pathData) {
  const points = {
    list: [],
    minX: 0,
    minY: 0,
    maxX: 0,
    maxY: 0
  }, addPoint = (path, point) => {
    (!path.list.length || point[1] > path.list[path.maxY][1]) && (path.maxY = path.list.length, 
    points.maxY = points.list.length ? Math.max(point[1], points.maxY) : point[1]), 
    (!path.list.length || point[0] > path.list[path.maxX][0]) && (path.maxX = path.list.length, 
    points.maxX = points.list.length ? Math.max(point[0], points.maxX) : point[0]), 
    (!path.list.length || point[1] < path.list[path.minY][1]) && (path.minY = path.list.length, 
    points.minY = points.list.length ? Math.min(point[1], points.minY) : point[1]), 
    (!path.list.length || point[0] < path.list[path.minX][0]) && (path.minX = path.list.length, 
    points.minX = points.list.length ? Math.min(point[0], points.minX) : point[0]), 
    path.list.push(point);
  };
  for (let i = 0; i < pathData.length; i += 1) {
    const pathDataItem = pathData[i];
    let subPath = 0 === points.list.length ? {
      list: [],
      minX: 0,
      minY: 0,
      maxX: 0,
      maxY: 0
    } : points.list[points.list.length - 1], prev = 0 === i ? null : pathData[i - 1], basePoint = 0 === subPath.list.length ? null : subPath.list[subPath.list.length - 1], data = pathDataItem.args, ctrlPoint = basePoint;
    const toAbsolute = (n, i) => n + (null == basePoint ? 0 : basePoint[i % 2]);
    switch (pathDataItem.command) {
     case "M":
      subPath = {
        list: [],
        minX: 0,
        minY: 0,
        maxX: 0,
        maxY: 0
      }, points.list.push(subPath);
      break;

     case "H":
      null != basePoint && addPoint(subPath, [ data[0], basePoint[1] ]);
      break;

     case "V":
      null != basePoint && addPoint(subPath, [ basePoint[0], data[0] ]);
      break;

     case "Q":
      addPoint(subPath, data.slice(0, 2)), prevCtrlPoint = [ data[2] - data[0], data[3] - data[1] ];
      break;

     case "T":
      null == basePoint || null == prev || "Q" != prev.command && "T" != prev.command || (ctrlPoint = [ basePoint[0] + prevCtrlPoint[0], basePoint[1] + prevCtrlPoint[1] ], 
      addPoint(subPath, ctrlPoint), prevCtrlPoint = [ data[0] - ctrlPoint[0], data[1] - ctrlPoint[1] ]);
      break;

     case "C":
      null != basePoint && addPoint(subPath, [ 0.5 * (basePoint[0] + data[0]), 0.5 * (basePoint[1] + data[1]) ]), 
      addPoint(subPath, [ 0.5 * (data[0] + data[2]), 0.5 * (data[1] + data[3]) ]), addPoint(subPath, [ 0.5 * (data[2] + data[4]), 0.5 * (data[3] + data[5]) ]), 
      prevCtrlPoint = [ data[4] - data[2], data[5] - data[3] ];
      break;

     case "S":
      null == basePoint || null == prev || "C" != prev.command && "S" != prev.command || (addPoint(subPath, [ basePoint[0] + 0.5 * prevCtrlPoint[0], basePoint[1] + 0.5 * prevCtrlPoint[1] ]), 
      ctrlPoint = [ basePoint[0] + prevCtrlPoint[0], basePoint[1] + prevCtrlPoint[1] ]), 
      null != ctrlPoint && addPoint(subPath, [ 0.5 * (ctrlPoint[0] + data[0]), 0.5 * (ctrlPoint[1] + data[1]) ]), 
      addPoint(subPath, [ 0.5 * (data[0] + data[2]), 0.5 * (data[1] + data[3]) ]), prevCtrlPoint = [ data[2] - data[0], data[3] - data[1] ];
      break;

     case "A":
      if (null != basePoint) for (var cData, curves = a2c.apply(0, basePoint.concat(data)); (cData = curves.splice(0, 6).map(toAbsolute)).length; ) null != basePoint && addPoint(subPath, [ 0.5 * (basePoint[0] + cData[0]), 0.5 * (basePoint[1] + cData[1]) ]), 
      addPoint(subPath, [ 0.5 * (cData[0] + cData[2]), 0.5 * (cData[1] + cData[3]) ]), 
      addPoint(subPath, [ 0.5 * (cData[2] + cData[4]), 0.5 * (cData[3] + cData[5]) ]), 
      curves.length && addPoint(subPath, basePoint = cData.slice(-2));
    }
    data.length >= 2 && addPoint(subPath, data.slice(-2));
  }
  return points;
}

function convexHull(points) {
  points.list.sort((function(a, b) {
    return a[0] == b[0] ? a[1] - b[1] : a[0] - b[0];
  }));
  var lower = [], minY = 0, bottom = 0;
  for (let i = 0; i < points.list.length; i++) {
    for (;lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], points.list[i]) <= 0; ) lower.pop();
    points.list[i][1] < points.list[minY][1] && (minY = i, bottom = lower.length), lower.push(points.list[i]);
  }
  var upper = [], maxY = points.list.length - 1, top = 0;
  for (let i = points.list.length; i--; ) {
    for (;upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], points.list[i]) <= 0; ) upper.pop();
    points.list[i][1] > points.list[maxY][1] && (maxY = i, top = upper.length), upper.push(points.list[i]);
  }
  upper.pop(), lower.pop();
  const hullList = lower.concat(upper);
  return {
    list: hullList,
    minX: 0,
    maxX: lower.length,
    minY: bottom,
    maxY: (lower.length + top) % hullList.length
  };
}

function cross(o, a, b) {
  return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
}

_path.js2path = function(path, data, params) {
  path.pathJS = data;
  const pathData = [];
  for (const item of data) {
    if (0 !== pathData.length && ("M" === item.command || "m" === item.command)) {
      const last = pathData[pathData.length - 1];
      "M" !== last.command && "m" !== last.command || pathData.pop();
    }
    pathData.push({
      command: item.command,
      args: item.args
    });
  }
  path.attributes.d = stringifyPathData({
    pathData,
    precision: params.floatPrecision,
    disableSpaceAfterFlags: params.noSpaceAfterFlags
  });
}, _path.intersects = function(path1, path2) {
  const points1 = gatherPoints(convertRelativeToAbsolute(path1)), points2 = gatherPoints(convertRelativeToAbsolute(path2));
  if (points1.maxX <= points2.minX || points2.maxX <= points1.minX || points1.maxY <= points2.minY || points2.maxY <= points1.minY || points1.list.every((set1 => points2.list.every((set2 => set1.list[set1.maxX][0] <= set2.list[set2.minX][0] || set2.list[set2.maxX][0] <= set1.list[set1.minX][0] || set1.list[set1.maxY][1] <= set2.list[set2.minY][1] || set2.list[set2.maxY][1] <= set1.list[set1.minY][1]))))) return !1;
  const hullNest1 = points1.list.map(convexHull), hullNest2 = points2.list.map(convexHull);
  return hullNest1.some((function(hull1) {
    return !(hull1.list.length < 3) && hullNest2.some((function(hull2) {
      if (hull2.list.length < 3) return !1;
      for (var simplex = [ getSupport(hull1, hull2, [ 1, 0 ]) ], direction = minus(simplex[0]), iterations = 1e4; ;) {
        if (0 == iterations--) return console.error("Error: infinite loop while processing mergePaths plugin."), 
        !0;
        if (simplex.push(getSupport(hull1, hull2, direction)), dot(direction, simplex[simplex.length - 1]) <= 0) return !1;
        if (processSimplex(simplex, direction)) return !0;
      }
    }));
  }));
  function getSupport(a, b, direction) {
    return sub(supportPoint(a, direction), supportPoint(b, minus(direction)));
  }
  function supportPoint(polygon, direction) {
    for (var value, index = direction[1] >= 0 ? direction[0] < 0 ? polygon.maxY : polygon.maxX : direction[0] < 0 ? polygon.minX : polygon.minY, max = -1 / 0; (value = dot(polygon.list[index], direction)) > max; ) max = value, 
    index = ++index % polygon.list.length;
    return polygon.list[(index || polygon.list.length) - 1];
  }
};

const a2c = (x1, y1, rx, ry, angle, large_arc_flag, sweep_flag, x2, y2, recursive) => {
  const _120 = 120 * Math.PI / 180, rad = Math.PI / 180 * (+angle || 0);
  let res = [];
  const rotateX = (x, y, rad) => x * Math.cos(rad) - y * Math.sin(rad), rotateY = (x, y, rad) => x * Math.sin(rad) + y * Math.cos(rad);
  if (recursive) f1 = recursive[0], f2 = recursive[1], cx = recursive[2], cy = recursive[3]; else {
    y1 = rotateY(x1 = rotateX(x1, y1, -rad), y1, -rad);
    var x = (x1 - (x2 = rotateX(x2, y2, -rad))) / 2, y = (y1 - (y2 = rotateY(x2, y2, -rad))) / 2, h = x * x / (rx * rx) + y * y / (ry * ry);
    h > 1 && (rx *= h = Math.sqrt(h), ry *= h);
    var rx2 = rx * rx, ry2 = ry * ry, k = (large_arc_flag == sweep_flag ? -1 : 1) * Math.sqrt(Math.abs((rx2 * ry2 - rx2 * y * y - ry2 * x * x) / (rx2 * y * y + ry2 * x * x))), cx = k * rx * y / ry + (x1 + x2) / 2, cy = k * -ry * x / rx + (y1 + y2) / 2, f1 = Math.asin(Number(((y1 - cy) / ry).toFixed(9))), f2 = Math.asin(Number(((y2 - cy) / ry).toFixed(9)));
    f1 = x1 < cx ? Math.PI - f1 : f1, f2 = x2 < cx ? Math.PI - f2 : f2, f1 < 0 && (f1 = 2 * Math.PI + f1), 
    f2 < 0 && (f2 = 2 * Math.PI + f2), sweep_flag && f1 > f2 && (f1 -= 2 * Math.PI), 
    !sweep_flag && f2 > f1 && (f2 -= 2 * Math.PI);
  }
  var df = f2 - f1;
  if (Math.abs(df) > _120) {
    var f2old = f2, x2old = x2, y2old = y2;
    f2 = f1 + _120 * (sweep_flag && f2 > f1 ? 1 : -1), x2 = cx + rx * Math.cos(f2), 
    y2 = cy + ry * Math.sin(f2), res = a2c(x2, y2, rx, ry, angle, 0, sweep_flag, x2old, y2old, [ f2, f2old, cx, cy ]);
  }
  df = f2 - f1;
  var c1 = Math.cos(f1), s1 = Math.sin(f1), c2 = Math.cos(f2), s2 = Math.sin(f2), t = Math.tan(df / 4), hx = 4 / 3 * rx * t, hy = 4 / 3 * ry * t, m = [ -hx * s1, hy * c1, x2 + hx * s2 - x1, y2 - hy * c2 - y1, x2 - x1, y2 - y1 ];
  if (recursive) return m.concat(res);
  res = m.concat(res);
  for (var newres = [], i = 0, n = res.length; i < n; i++) newres[i] = i % 2 ? rotateY(res[i - 1], res[i], rad) : rotateX(res[i], res[i + 1], rad);
  return newres;
};

var applyTransforms$2 = {}, _transforms = {};

const regTransformTypes = /matrix|translate|scale|rotate|skewX|skewY/, regTransformSplit = /\s*(matrix|translate|scale|rotate|skewX|skewY)\s*\(\s*(.+?)\s*\)[\s,]*/, regNumericValues$2 = /[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?/g;

_transforms.transform2js = transformString => {
  const transforms = [];
  let current = null;
  for (const item of transformString.split(regTransformSplit)) {
    var num;
    if (item) if (regTransformTypes.test(item)) current = {
      name: item,
      data: []
    }, transforms.push(current); else for (;num = regNumericValues$2.exec(item); ) num = Number(num), 
    null != current && current.data.push(num);
  }
  return null == current || 0 == current.data.length ? [] : transforms;
}, _transforms.transformsMultiply = transforms => {
  const matrixData = transforms.map((transform => "matrix" === transform.name ? transform.data : transformToMatrix(transform)));
  return {
    name: "matrix",
    data: matrixData.length > 0 ? matrixData.reduce(multiplyTransformMatrices) : []
  };
};

const mth = {
  rad: deg => deg * Math.PI / 180,
  deg: rad => 180 * rad / Math.PI,
  cos: deg => Math.cos(mth.rad(deg)),
  acos: (val, floatPrecision) => Number(mth.deg(Math.acos(val)).toFixed(floatPrecision)),
  sin: deg => Math.sin(mth.rad(deg)),
  asin: (val, floatPrecision) => Number(mth.deg(Math.asin(val)).toFixed(floatPrecision)),
  tan: deg => Math.tan(mth.rad(deg)),
  atan: (val, floatPrecision) => Number(mth.deg(Math.atan(val)).toFixed(floatPrecision))
};

_transforms.matrixToTransform = (transform, params) => {
  let floatPrecision = params.floatPrecision, data = transform.data, transforms = [], sx = Number(Math.hypot(data[0], data[1]).toFixed(params.transformPrecision)), sy = Number(((data[0] * data[3] - data[1] * data[2]) / sx).toFixed(params.transformPrecision)), colsSum = data[0] * data[2] + data[1] * data[3], rowsSum = data[0] * data[1] + data[2] * data[3], scaleBefore = 0 != rowsSum || sx == sy;
  if ((data[4] || data[5]) && transforms.push({
    name: "translate",
    data: data.slice(4, data[5] ? 6 : 5)
  }), !data[1] && data[2]) transforms.push({
    name: "skewX",
    data: [ mth.atan(data[2] / sy, floatPrecision) ]
  }); else if (data[1] && !data[2]) transforms.push({
    name: "skewY",
    data: [ mth.atan(data[1] / data[0], floatPrecision) ]
  }), sx = data[0], sy = data[3]; else if (!colsSum || 1 == sx && 1 == sy || !scaleBefore) {
    scaleBefore || (sx = (data[0] < 0 ? -1 : 1) * Math.hypot(data[0], data[2]), sy = (data[3] < 0 ? -1 : 1) * Math.hypot(data[1], data[3]), 
    transforms.push({
      name: "scale",
      data: [ sx, sy ]
    }));
    var angle = Math.min(Math.max(-1, data[0] / sx), 1), rotate = [ mth.acos(angle, floatPrecision) * ((scaleBefore ? 1 : sy) * data[1] < 0 ? -1 : 1) ];
    if (rotate[0] && transforms.push({
      name: "rotate",
      data: rotate
    }), rowsSum && colsSum && transforms.push({
      name: "skewX",
      data: [ mth.atan(colsSum / (sx * sx), floatPrecision) ]
    }), rotate[0] && (data[4] || data[5])) {
      transforms.shift();
      var cos = data[0] / sx, sin = data[1] / (scaleBefore ? sx : sy), x = data[4] * (scaleBefore ? 1 : sy), y = data[5] * (scaleBefore ? 1 : sx), denom = (Math.pow(1 - cos, 2) + Math.pow(sin, 2)) * (scaleBefore ? 1 : sx * sy);
      rotate.push(((1 - cos) * x - sin * y) / denom), rotate.push(((1 - cos) * y + sin * x) / denom);
    }
  } else if (data[1] || data[2]) return [ transform ];
  return (!scaleBefore || 1 == sx && 1 == sy) && transforms.length || transforms.push({
    name: "scale",
    data: sx == sy ? [ sx ] : [ sx, sy ]
  }), transforms;
};

const transformToMatrix = transform => {
  if ("matrix" === transform.name) return transform.data;
  switch (transform.name) {
   case "translate":
    return [ 1, 0, 0, 1, transform.data[0], transform.data[1] || 0 ];

   case "scale":
    return [ transform.data[0], 0, 0, transform.data[1] || transform.data[0], 0, 0 ];

   case "rotate":
    var cos = mth.cos(transform.data[0]), sin = mth.sin(transform.data[0]), cx = transform.data[1] || 0, cy = transform.data[2] || 0;
    return [ cos, sin, -sin, cos, (1 - cos) * cx + sin * cy, (1 - cos) * cy - sin * cx ];

   case "skewX":
    return [ 1, 0, mth.tan(transform.data[0]), 1, 0, 0 ];

   case "skewY":
    return [ 1, mth.tan(transform.data[0]), 0, 1, 0, 0 ];

   default:
    throw Error(`Unknown transform ${transform.name}`);
  }
};

_transforms.transformArc = (cursor, arc, transform) => {
  const x = arc[5] - cursor[0], y = arc[6] - cursor[1];
  let a = arc[0], b = arc[1];
  const rot = arc[2] * Math.PI / 180, cos = Math.cos(rot), sin = Math.sin(rot);
  if (a > 0 && b > 0) {
    let h = Math.pow(x * cos + y * sin, 2) / (4 * a * a) + Math.pow(y * cos - x * sin, 2) / (4 * b * b);
    h > 1 && (h = Math.sqrt(h), a *= h, b *= h);
  }
  const m = multiplyTransformMatrices(transform, [ a * cos, a * sin, -b * sin, b * cos, 0, 0 ]), lastCol = m[2] * m[2] + m[3] * m[3], squareSum = m[0] * m[0] + m[1] * m[1] + lastCol, root = Math.hypot(m[0] - m[3], m[1] + m[2]) * Math.hypot(m[0] + m[3], m[1] - m[2]);
  if (root) {
    const majorAxisSqr = (squareSum + root) / 2, minorAxisSqr = (squareSum - root) / 2, major = Math.abs(majorAxisSqr - lastCol) > 1e-6, sub = (major ? majorAxisSqr : minorAxisSqr) - lastCol, rowsSum = m[0] * m[2] + m[1] * m[3], term1 = m[0] * sub + m[2] * rowsSum, term2 = m[1] * sub + m[3] * rowsSum;
    arc[0] = Math.sqrt(majorAxisSqr), arc[1] = Math.sqrt(minorAxisSqr), arc[2] = ((major ? term2 < 0 : term1 > 0) ? -1 : 1) * Math.acos((major ? term1 : term2) / Math.hypot(term1, term2)) * 180 / Math.PI;
  } else arc[0] = arc[1] = Math.sqrt(squareSum / 2), arc[2] = 0;
  return transform[0] < 0 != transform[3] < 0 && (arc[4] = 1 - arc[4]), arc;
};

const multiplyTransformMatrices = (a, b) => [ a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1], a[0] * b[2] + a[2] * b[3], a[1] * b[2] + a[3] * b[3], a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5] ], {collectStylesheet: collectStylesheet$2, computeStyle: computeStyle$2} = style, {transformsMultiply: transformsMultiply$1, transform2js: transform2js$1, transformArc} = _transforms, {path2js: path2js$2} = _path, {removeLeadingZero: removeLeadingZero$1} = tools, {referencesProps: referencesProps$1, attrsGroupsDefaults} = _collections, regNumericValues$1 = /[-+]?(\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?/g;

applyTransforms$2.applyTransforms = (root, params) => {
  const stylesheet = collectStylesheet$2(root);
  return {
    element: {
      enter: node => {
        const computedStyle = computeStyle$2(stylesheet, node);
        if (null == node.attributes.d) return;
        if (null != node.attributes.id) return;
        if (null == node.attributes.transform || "" === node.attributes.transform || null != node.attributes.style || Object.entries(node.attributes).some((([name, value]) => referencesProps$1.includes(name) && value.includes("url(")))) return;
        const matrix = transformsMultiply$1(transform2js$1(node.attributes.transform)), stroke = null != computedStyle.stroke && "static" === computedStyle.stroke.type ? computedStyle.stroke.value : null, strokeWidth = null != computedStyle["stroke-width"] && "static" === computedStyle["stroke-width"].type ? computedStyle["stroke-width"].value : null, transformPrecision = params.transformPrecision;
        if (null != computedStyle.stroke && "dynamic" === computedStyle.stroke.type || null != computedStyle.strokeWidth && "dynamic" === computedStyle["stroke-width"].type) return;
        const scale = Number(Math.sqrt(matrix.data[0] * matrix.data[0] + matrix.data[1] * matrix.data[1]).toFixed(transformPrecision));
        if (stroke && "none" != stroke) {
          if (!1 === params.applyTransformsStroked) return;
          if (!(matrix.data[0] === matrix.data[3] && matrix.data[1] === -matrix.data[2] || matrix.data[0] === -matrix.data[3] && matrix.data[1] === matrix.data[2])) return;
          1 !== scale && "non-scaling-stroke" !== node.attributes["vector-effect"] && (node.attributes["stroke-width"] = (strokeWidth || attrsGroupsDefaults.presentation["stroke-width"]).trim().replace(regNumericValues$1, (num => removeLeadingZero$1(Number(num) * scale))), 
          null != node.attributes["stroke-dashoffset"] && (node.attributes["stroke-dashoffset"] = node.attributes["stroke-dashoffset"].trim().replace(regNumericValues$1, (num => removeLeadingZero$1(Number(num) * scale)))), 
          null != node.attributes["stroke-dasharray"] && (node.attributes["stroke-dasharray"] = node.attributes["stroke-dasharray"].trim().replace(regNumericValues$1, (num => removeLeadingZero$1(Number(num) * scale)))));
        }
        const pathData = path2js$2(node);
        applyMatrixToPathData(pathData, matrix.data), delete node.attributes.transform;
      }
    }
  };
};

const transformAbsolutePoint = (matrix, x, y) => [ matrix[0] * x + matrix[2] * y + matrix[4], matrix[1] * x + matrix[3] * y + matrix[5] ], transformRelativePoint = (matrix, x, y) => [ matrix[0] * x + matrix[2] * y, matrix[1] * x + matrix[3] * y ], applyMatrixToPathData = (pathData, matrix) => {
  const start = [ 0, 0 ], cursor = [ 0, 0 ];
  for (const pathItem of pathData) {
    let {command, args} = pathItem;
    if ("M" === command) {
      cursor[0] = args[0], cursor[1] = args[1], start[0] = cursor[0], start[1] = cursor[1];
      const [x, y] = transformAbsolutePoint(matrix, args[0], args[1]);
      args[0] = x, args[1] = y;
    }
    if ("m" === command) {
      cursor[0] += args[0], cursor[1] += args[1], start[0] = cursor[0], start[1] = cursor[1];
      const [x, y] = transformRelativePoint(matrix, args[0], args[1]);
      args[0] = x, args[1] = y;
    }
    if ("H" === command && (command = "L", args = [ args[0], cursor[1] ]), "h" === command && (command = "l", 
    args = [ args[0], 0 ]), "V" === command && (command = "L", args = [ cursor[0], args[0] ]), 
    "v" === command && (command = "l", args = [ 0, args[0] ]), "L" === command) {
      cursor[0] = args[0], cursor[1] = args[1];
      const [x, y] = transformAbsolutePoint(matrix, args[0], args[1]);
      args[0] = x, args[1] = y;
    }
    if ("l" === command) {
      cursor[0] += args[0], cursor[1] += args[1];
      const [x, y] = transformRelativePoint(matrix, args[0], args[1]);
      args[0] = x, args[1] = y;
    }
    if ("C" === command) {
      cursor[0] = args[4], cursor[1] = args[5];
      const [x1, y1] = transformAbsolutePoint(matrix, args[0], args[1]), [x2, y2] = transformAbsolutePoint(matrix, args[2], args[3]), [x, y] = transformAbsolutePoint(matrix, args[4], args[5]);
      args[0] = x1, args[1] = y1, args[2] = x2, args[3] = y2, args[4] = x, args[5] = y;
    }
    if ("c" === command) {
      cursor[0] += args[4], cursor[1] += args[5];
      const [x1, y1] = transformRelativePoint(matrix, args[0], args[1]), [x2, y2] = transformRelativePoint(matrix, args[2], args[3]), [x, y] = transformRelativePoint(matrix, args[4], args[5]);
      args[0] = x1, args[1] = y1, args[2] = x2, args[3] = y2, args[4] = x, args[5] = y;
    }
    if ("S" === command) {
      cursor[0] = args[2], cursor[1] = args[3];
      const [x2, y2] = transformAbsolutePoint(matrix, args[0], args[1]), [x, y] = transformAbsolutePoint(matrix, args[2], args[3]);
      args[0] = x2, args[1] = y2, args[2] = x, args[3] = y;
    }
    if ("s" === command) {
      cursor[0] += args[2], cursor[1] += args[3];
      const [x2, y2] = transformRelativePoint(matrix, args[0], args[1]), [x, y] = transformRelativePoint(matrix, args[2], args[3]);
      args[0] = x2, args[1] = y2, args[2] = x, args[3] = y;
    }
    if ("Q" === command) {
      cursor[0] = args[2], cursor[1] = args[3];
      const [x1, y1] = transformAbsolutePoint(matrix, args[0], args[1]), [x, y] = transformAbsolutePoint(matrix, args[2], args[3]);
      args[0] = x1, args[1] = y1, args[2] = x, args[3] = y;
    }
    if ("q" === command) {
      cursor[0] += args[2], cursor[1] += args[3];
      const [x1, y1] = transformRelativePoint(matrix, args[0], args[1]), [x, y] = transformRelativePoint(matrix, args[2], args[3]);
      args[0] = x1, args[1] = y1, args[2] = x, args[3] = y;
    }
    if ("T" === command) {
      cursor[0] = args[0], cursor[1] = args[1];
      const [x, y] = transformAbsolutePoint(matrix, args[0], args[1]);
      args[0] = x, args[1] = y;
    }
    if ("t" === command) {
      cursor[0] += args[0], cursor[1] += args[1];
      const [x, y] = transformRelativePoint(matrix, args[0], args[1]);
      args[0] = x, args[1] = y;
    }
    if ("A" === command) {
      if (transformArc(cursor, args, matrix), cursor[0] = args[5], cursor[1] = args[6], 
      Math.abs(args[2]) > 80) {
        const a = args[0], rotation = args[2];
        args[0] = args[1], args[1] = a, args[2] = rotation + (rotation > 0 ? -90 : 90);
      }
      const [x, y] = transformAbsolutePoint(matrix, args[5], args[6]);
      args[5] = x, args[6] = y;
    }
    if ("a" === command) {
      if (transformArc([ 0, 0 ], args, matrix), cursor[0] += args[5], cursor[1] += args[6], 
      Math.abs(args[2]) > 80) {
        const a = args[0], rotation = args[2];
        args[0] = args[1], args[1] = a, args[2] = rotation + (rotation > 0 ? -90 : 90);
      }
      const [x, y] = transformRelativePoint(matrix, args[5], args[6]);
      args[5] = x, args[6] = y;
    }
    "z" !== command && "Z" !== command || (cursor[0] = start[0], cursor[1] = start[1]), 
    pathItem.command = command, pathItem.args = args;
  }
}, {collectStylesheet: collectStylesheet$1, computeStyle: computeStyle$1} = style, {visit} = xast, {pathElems} = _collections, {path2js: path2js$1, js2path: js2path$1} = _path, {applyTransforms} = applyTransforms$2, {cleanupOutData: cleanupOutData$1} = tools;

let roundData, precision, error, arcThreshold, arcTolerance;

convertPathData$2.name = "convertPathData", convertPathData$2.description = "optimizes path data: writes in shorter form, applies transformations", 
convertPathData$2.fn = (root, params) => {
  const {applyTransforms: _applyTransforms = !0, applyTransformsStroked = !0, makeArcs = {
    threshold: 2.5,
    tolerance: 0.5
  }, straightCurves = !0, lineShorthands = !0, curveSmoothShorthands = !0, floatPrecision = 3, transformPrecision = 5, removeUseless = !0, collapseRepeated = !0, utilizeAbsolute = !0, leadingZero = !0, negativeExtraSpace = !0, noSpaceAfterFlags = !1, forceAbsolutePath = !1} = params, newParams = {
    applyTransforms: _applyTransforms,
    applyTransformsStroked,
    makeArcs,
    straightCurves,
    lineShorthands,
    curveSmoothShorthands,
    floatPrecision,
    transformPrecision,
    removeUseless,
    collapseRepeated,
    utilizeAbsolute,
    leadingZero,
    negativeExtraSpace,
    noSpaceAfterFlags,
    forceAbsolutePath
  };
  _applyTransforms && visit(root, applyTransforms(root, {
    transformPrecision,
    applyTransformsStroked
  }));
  const stylesheet = collectStylesheet$1(root);
  return {
    element: {
      enter: node => {
        if (pathElems.includes(node.name) && null != node.attributes.d) {
          const computedStyle = computeStyle$1(stylesheet, node);
          precision = floatPrecision, error = !1 !== precision ? +Math.pow(0.1, precision).toFixed(precision) : 1e-2, 
          roundData = precision > 0 && precision < 20 ? strongRound : round$1, makeArcs && (arcThreshold = makeArcs.threshold, 
          arcTolerance = makeArcs.tolerance);
          const hasMarkerMid = null != computedStyle["marker-mid"], maybeHasStroke = computedStyle.stroke && ("dynamic" === computedStyle.stroke.type || "none" !== computedStyle.stroke.value), maybeHasLinecap = computedStyle["stroke-linecap"] && ("dynamic" === computedStyle["stroke-linecap"].type || "butt" !== computedStyle["stroke-linecap"].value), maybeHasStrokeAndLinecap = maybeHasStroke && maybeHasLinecap;
          var data = path2js$1(node);
          data.length && (convertToRelative(data), data = function(path, params, {maybeHasStrokeAndLinecap, hasMarkerMid}) {
            var stringify = data2Path.bind(null, params), relSubpoint = [ 0, 0 ], pathBase = [ 0, 0 ], prev = {};
            return path = path.filter((function(item, index, path) {
              let command = item.command, data = item.args, next = path[index + 1];
              if ("Z" !== command && "z" !== command) {
                var circle, sdata = data;
                if ("s" === command) {
                  sdata = [ 0, 0 ].concat(data);
                  var pdata = prev.args, n = pdata.length;
                  sdata[0] = pdata[n - 2] - pdata[n - 4], sdata[1] = pdata[n - 1] - pdata[n - 3];
                }
                if (params.makeArcs && ("c" == command || "s" == command) && isConvex(sdata) && (circle = function(curve) {
                  var midPoint = getCubicBezierPoint(curve, .5), m1 = [ midPoint[0] / 2, midPoint[1] / 2 ], m2 = [ (midPoint[0] + curve[4]) / 2, (midPoint[1] + curve[5]) / 2 ], center = getIntersection([ m1[0], m1[1], m1[0] + m1[1], m1[1] - m1[0], m2[0], m2[1], m2[0] + (m2[1] - midPoint[1]), m2[1] - (m2[0] - midPoint[0]) ]), radius = center && getDistance([ 0, 0 ], center), tolerance = Math.min(arcThreshold * error, arcTolerance * radius / 100);
                  if (center && radius < 1e15 && [ 1 / 4, 3 / 4 ].every((function(point) {
                    return Math.abs(getDistance(getCubicBezierPoint(curve, point), center) - radius) <= tolerance;
                  }))) return {
                    center,
                    radius
                  };
                }(sdata))) {
                  var nextLonghand, r = roundData([ circle.radius ])[0], angle = findArcAngle(sdata, circle), sweep = sdata[5] * sdata[0] - sdata[4] * sdata[1] > 0 ? 1 : 0, arc = {
                    command: "a",
                    args: [ r, r, 0, 0, sweep, sdata[4], sdata[5] ],
                    coords: item.coords.slice(),
                    base: item.base
                  }, output = [ arc ], relCenter = [ circle.center[0] - sdata[4], circle.center[1] - sdata[5] ], relCircle = {
                    center: relCenter,
                    radius: circle.radius
                  }, arcCurves = [ item ], hasPrev = 0, suffix = "";
                  if ("c" == prev.command && isConvex(prev.args) && isArcPrev(prev.args, circle) || "a" == prev.command && prev.sdata && isArcPrev(prev.sdata, circle)) {
                    arcCurves.unshift(prev), arc.base = prev.base, arc.args[5] = arc.coords[0] - arc.base[0], 
                    arc.args[6] = arc.coords[1] - arc.base[1];
                    var prevData = "a" == prev.command ? prev.sdata : prev.args;
                    (angle += findArcAngle(prevData, {
                      center: [ prevData[4] + circle.center[0], prevData[5] + circle.center[1] ],
                      radius: circle.radius
                    })) > Math.PI && (arc.args[3] = 1), hasPrev = 1;
                  }
                  for (var j = index; (next = path[++j]) && ~"cs".indexOf(next.command); ) {
                    var nextData = next.args;
                    if ("s" == next.command && (nextData = (nextLonghand = makeLonghand({
                      command: "s",
                      args: next.args.slice()
                    }, path[j - 1].args)).args, nextLonghand.args = nextData.slice(0, 2), suffix = stringify([ nextLonghand ])), 
                    !isConvex(nextData) || !isArc(nextData, relCircle)) break;
                    if ((angle += findArcAngle(nextData, relCircle)) - 2 * Math.PI > 1e-3) break;
                    if (angle > Math.PI && (arc.args[3] = 1), arcCurves.push(next), !(2 * Math.PI - angle > 1e-3)) {
                      arc.args[5] = 2 * (relCircle.center[0] - nextData[4]), arc.args[6] = 2 * (relCircle.center[1] - nextData[5]), 
                      arc.coords = [ arc.base[0] + arc.args[5], arc.base[1] + arc.args[6] ], arc = {
                        command: "a",
                        args: [ r, r, 0, 0, sweep, next.coords[0] - arc.coords[0], next.coords[1] - arc.coords[1] ],
                        coords: next.coords,
                        base: arc.coords
                      }, output.push(arc), j++;
                      break;
                    }
                    arc.coords = next.coords, arc.args[5] = arc.coords[0] - arc.base[0], arc.args[6] = arc.coords[1] - arc.base[1], 
                    relCenter[0] -= nextData[4], relCenter[1] -= nextData[5];
                  }
                  if ((stringify(output) + suffix).length < stringify(arcCurves).length) {
                    if (path[j] && "s" == path[j].command && makeLonghand(path[j], path[j - 1].args), 
                    hasPrev) {
                      var prevArc = output.shift();
                      roundData(prevArc.args), relSubpoint[0] += prevArc.args[5] - prev.args[prev.args.length - 2], 
                      relSubpoint[1] += prevArc.args[6] - prev.args[prev.args.length - 1], prev.command = "a", 
                      prev.args = prevArc.args, item.base = prev.coords = prevArc.coords;
                    }
                    if (arc = output.shift(), 1 == arcCurves.length ? item.sdata = sdata.slice() : arcCurves.length - 1 - hasPrev > 0 && path.splice.apply(path, [ index + 1, arcCurves.length - 1 - hasPrev ].concat(output)), 
                    !arc) return !1;
                    command = "a", data = arc.args, item.coords = arc.coords;
                  }
                }
                if (!1 !== precision) {
                  if ("m" === command || "l" === command || "t" === command || "q" === command || "s" === command || "c" === command) for (var i = data.length; i--; ) data[i] += item.base[i % 2] - relSubpoint[i % 2]; else "h" == command ? data[0] += item.base[0] - relSubpoint[0] : "v" == command ? data[0] += item.base[1] - relSubpoint[1] : "a" == command && (data[5] += item.base[0] - relSubpoint[0], 
                  data[6] += item.base[1] - relSubpoint[1]);
                  roundData(data), "h" == command ? relSubpoint[0] += data[0] : "v" == command ? relSubpoint[1] += data[0] : (relSubpoint[0] += data[data.length - 2], 
                  relSubpoint[1] += data[data.length - 1]), roundData(relSubpoint), "M" !== command && "m" !== command || (pathBase[0] = relSubpoint[0], 
                  pathBase[1] = relSubpoint[1]);
                }
                if (params.straightCurves && ("c" === command && isCurveStraightLine(data) || "s" === command && isCurveStraightLine(sdata) ? (next && "s" == next.command && makeLonghand(next, data), 
                command = "l", data = data.slice(-2)) : "q" === command && isCurveStraightLine(data) ? (next && "t" == next.command && makeLonghand(next, data), 
                command = "l", data = data.slice(-2)) : "t" === command && "q" !== prev.command && "t" !== prev.command ? (command = "l", 
                data = data.slice(-2)) : "a" !== command || 0 !== data[0] && 0 !== data[1] || (command = "l", 
                data = data.slice(-2))), params.lineShorthands && "l" === command && (0 === data[1] ? (command = "h", 
                data.pop()) : 0 === data[0] && (command = "v", data.shift())), params.collapseRepeated && !1 === hasMarkerMid && ("m" === command || "h" === command || "v" === command) && prev.command && command == prev.command.toLowerCase() && ("h" != command && "v" != command || prev.args[0] >= 0 == data[0] >= 0)) return prev.args[0] += data[0], 
                "h" != command && "v" != command && (prev.args[1] += data[1]), prev.coords = item.coords, 
                path[index] = prev, !1;
                if (params.curveSmoothShorthands && prev.command && ("c" === command ? ("c" === prev.command && data[0] === -(prev.args[2] - prev.args[4]) && data[1] === -(prev.args[3] - prev.args[5]) || "s" === prev.command && data[0] === -(prev.args[0] - prev.args[2]) && data[1] === -(prev.args[1] - prev.args[3]) || "c" !== prev.command && "s" !== prev.command && 0 === data[0] && 0 === data[1]) && (command = "s", 
                data = data.slice(2)) : "q" === command && ("q" === prev.command && data[0] === prev.args[2] - prev.args[0] && data[1] === prev.args[3] - prev.args[1] || "t" === prev.command && data[2] === prev.args[0] && data[3] === prev.args[1]) && (command = "t", 
                data = data.slice(2))), params.removeUseless && !maybeHasStrokeAndLinecap) {
                  if (("l" === command || "h" === command || "v" === command || "q" === command || "t" === command || "c" === command || "s" === command) && data.every((function(i) {
                    return 0 === i;
                  }))) return path[index] = prev, !1;
                  if ("a" === command && 0 === data[5] && 0 === data[6]) return path[index] = prev, 
                  !1;
                }
                item.command = command, item.args = data, prev = item;
              } else {
                if (relSubpoint[0] = pathBase[0], relSubpoint[1] = pathBase[1], "Z" === prev.command || "z" === prev.command) return !1;
                prev = item;
              }
              return !0;
            })), path;
          }(data, newParams, {
            maybeHasStrokeAndLinecap,
            hasMarkerMid
          }), utilizeAbsolute && (data = function(path, params) {
            var prev = path[0];
            return path = path.filter((function(item, index) {
              if (0 == index) return !0;
              if ("Z" === item.command || "z" === item.command) return prev = item, !0;
              var command = item.command, data = item.args, adata = data.slice();
              if ("m" === command || "l" === command || "t" === command || "q" === command || "s" === command || "c" === command) for (var i = adata.length; i--; ) adata[i] += item.base[i % 2]; else "h" == command ? adata[0] += item.base[0] : "v" == command ? adata[0] += item.base[1] : "a" == command && (adata[5] += item.base[0], 
              adata[6] += item.base[1]);
              roundData(adata);
              var absoluteDataStr = cleanupOutData$1(adata, params), relativeDataStr = cleanupOutData$1(data, params);
              return (params.forceAbsolutePath || absoluteDataStr.length < relativeDataStr.length && !(params.negativeExtraSpace && command == prev.command && prev.command.charCodeAt(0) > 96 && absoluteDataStr.length == relativeDataStr.length - 1 && (data[0] < 0 || /^0\./.test(data[0]) && prev.args[prev.args.length - 1] % 1))) && (item.command = command.toUpperCase(), 
              item.args = adata), prev = item, !0;
            })), path;
          }(data, newParams)), js2path$1(node, data, newParams));
        }
      }
    }
  };
};

const convertToRelative = pathData => {
  let start = [ 0, 0 ], cursor = [ 0, 0 ], prevCoords = [ 0, 0 ];
  for (let i = 0; i < pathData.length; i += 1) {
    const pathItem = pathData[i];
    let {command, args} = pathItem;
    "m" === command && (cursor[0] += args[0], cursor[1] += args[1], start[0] = cursor[0], 
    start[1] = cursor[1]), "M" === command && (0 !== i && (command = "m"), args[0] -= cursor[0], 
    args[1] -= cursor[1], cursor[0] += args[0], cursor[1] += args[1], start[0] = cursor[0], 
    start[1] = cursor[1]), "l" === command && (cursor[0] += args[0], cursor[1] += args[1]), 
    "L" === command && (command = "l", args[0] -= cursor[0], args[1] -= cursor[1], cursor[0] += args[0], 
    cursor[1] += args[1]), "h" === command && (cursor[0] += args[0]), "H" === command && (command = "h", 
    args[0] -= cursor[0], cursor[0] += args[0]), "v" === command && (cursor[1] += args[0]), 
    "V" === command && (command = "v", args[0] -= cursor[1], cursor[1] += args[0]), 
    "c" === command && (cursor[0] += args[4], cursor[1] += args[5]), "C" === command && (command = "c", 
    args[0] -= cursor[0], args[1] -= cursor[1], args[2] -= cursor[0], args[3] -= cursor[1], 
    args[4] -= cursor[0], args[5] -= cursor[1], cursor[0] += args[4], cursor[1] += args[5]), 
    "s" === command && (cursor[0] += args[2], cursor[1] += args[3]), "S" === command && (command = "s", 
    args[0] -= cursor[0], args[1] -= cursor[1], args[2] -= cursor[0], args[3] -= cursor[1], 
    cursor[0] += args[2], cursor[1] += args[3]), "q" === command && (cursor[0] += args[2], 
    cursor[1] += args[3]), "Q" === command && (command = "q", args[0] -= cursor[0], 
    args[1] -= cursor[1], args[2] -= cursor[0], args[3] -= cursor[1], cursor[0] += args[2], 
    cursor[1] += args[3]), "t" === command && (cursor[0] += args[0], cursor[1] += args[1]), 
    "T" === command && (command = "t", args[0] -= cursor[0], args[1] -= cursor[1], cursor[0] += args[0], 
    cursor[1] += args[1]), "a" === command && (cursor[0] += args[5], cursor[1] += args[6]), 
    "A" === command && (command = "a", args[5] -= cursor[0], args[6] -= cursor[1], cursor[0] += args[5], 
    cursor[1] += args[6]), "Z" !== command && "z" !== command || (cursor[0] = start[0], 
    cursor[1] = start[1]), pathItem.command = command, pathItem.args = args, pathItem.base = prevCoords, 
    pathItem.coords = [ cursor[0], cursor[1] ], prevCoords = pathItem.coords;
  }
  return pathData;
};

function isConvex(data) {
  var center = getIntersection([ 0, 0, data[2], data[3], data[0], data[1], data[4], data[5] ]);
  return null != center && data[2] < center[0] == center[0] < 0 && data[3] < center[1] == center[1] < 0 && data[4] < center[0] == center[0] < data[0] && data[5] < center[1] == center[1] < data[1];
}

function getIntersection(coords) {
  var a1 = coords[1] - coords[3], b1 = coords[2] - coords[0], c1 = coords[0] * coords[3] - coords[2] * coords[1], a2 = coords[5] - coords[7], b2 = coords[6] - coords[4], c2 = coords[4] * coords[7] - coords[5] * coords[6], denom = a1 * b2 - a2 * b1;
  if (denom) {
    var cross = [ (b1 * c2 - b2 * c1) / denom, (a1 * c2 - a2 * c1) / -denom ];
    return !isNaN(cross[0]) && !isNaN(cross[1]) && isFinite(cross[0]) && isFinite(cross[1]) ? cross : void 0;
  }
}

function toFixed(num, precision) {
  const pow = 10 ** precision;
  return Math.round(num * pow) / pow;
}

function strongRound(data) {
  const precisionNum = precision || 0;
  for (let i = data.length; i-- > 0; ) {
    const fixed = toFixed(data[i], precisionNum);
    if (fixed !== data[i]) {
      const rounded = toFixed(data[i], precisionNum - 1);
      data[i] = toFixed(Math.abs(rounded - data[i]), precisionNum + 1) >= error ? fixed : rounded;
    }
  }
  return data;
}

function round$1(data) {
  for (var i = data.length; i-- > 0; ) data[i] = Math.round(data[i]);
  return data;
}

function isCurveStraightLine(data) {
  var i = data.length - 2, a = -data[i + 1], b = data[i], d = 1 / (a * a + b * b);
  if (i <= 1 || !isFinite(d)) return !1;
  for (;(i -= 2) >= 0; ) if (Math.sqrt(Math.pow(a * data[i] + b * data[i + 1], 2) * d) > error) return !1;
  return !0;
}

function makeLonghand(item, data) {
  switch (item.command) {
   case "s":
    item.command = "c";
    break;

   case "t":
    item.command = "q";
  }
  return item.args.unshift(data[data.length - 2] - data[data.length - 4], data[data.length - 1] - data[data.length - 3]), 
  item;
}

function getDistance(point1, point2) {
  return Math.hypot(point1[0] - point2[0], point1[1] - point2[1]);
}

function getCubicBezierPoint(curve, t) {
  var sqrT = t * t, cubT = sqrT * t, mt = 1 - t, sqrMt = mt * mt;
  return [ 3 * sqrMt * t * curve[0] + 3 * mt * sqrT * curve[2] + cubT * curve[4], 3 * sqrMt * t * curve[1] + 3 * mt * sqrT * curve[3] + cubT * curve[5] ];
}

function isArc(curve, circle) {
  var tolerance = Math.min(arcThreshold * error, arcTolerance * circle.radius / 100);
  return [ 0, 1 / 4, .5, 3 / 4, 1 ].every((function(point) {
    return Math.abs(getDistance(getCubicBezierPoint(curve, point), circle.center) - circle.radius) <= tolerance;
  }));
}

function isArcPrev(curve, circle) {
  return isArc(curve, {
    center: [ circle.center[0] + curve[4], circle.center[1] + curve[5] ],
    radius: circle.radius
  });
}

function findArcAngle(curve, relCircle) {
  var x1 = -relCircle.center[0], y1 = -relCircle.center[1], x2 = curve[4] - relCircle.center[0], y2 = curve[5] - relCircle.center[1];
  return Math.acos((x1 * x2 + y1 * y2) / Math.sqrt((x1 * x1 + y1 * y1) * (x2 * x2 + y2 * y2)));
}

function data2Path(params, pathData) {
  return pathData.reduce((function(pathString, item) {
    var strData = "";
    return item.args && (strData = cleanupOutData$1(roundData(item.args.slice()), params)), 
    pathString + item.command + strData;
  }), "");
}

var convertTransform$3 = {};

const {cleanupOutData} = tools, {transform2js, transformsMultiply, matrixToTransform} = _transforms;

convertTransform$3.name = "convertTransform", convertTransform$3.description = "collapses multiple transformations and optimizes it", 
convertTransform$3.fn = (_root, params) => {
  const {convertToShorts = !0, degPrecision, floatPrecision = 3, transformPrecision = 5, matrixToTransform = !0, shortTranslate = !0, shortScale = !0, shortRotate = !0, removeUseless = !0, collapseIntoOne = !0, leadingZero = !0, negativeExtraSpace = !1} = params, newParams = {
    convertToShorts,
    degPrecision,
    floatPrecision,
    transformPrecision,
    matrixToTransform,
    shortTranslate,
    shortScale,
    shortRotate,
    removeUseless,
    collapseIntoOne,
    leadingZero,
    negativeExtraSpace
  };
  return {
    element: {
      enter: node => {
        null != node.attributes.transform && convertTransform$2(node, "transform", newParams), 
        null != node.attributes.gradientTransform && convertTransform$2(node, "gradientTransform", newParams), 
        null != node.attributes.patternTransform && convertTransform$2(node, "patternTransform", newParams);
      }
    }
  };
};

const convertTransform$2 = (item, attrName, params) => {
  let data = transform2js(item.attributes[attrName]);
  (params = definePrecision(data, params)).collapseIntoOne && data.length > 1 && (data = [ transformsMultiply(data) ]), 
  params.convertToShorts ? data = convertToShorts(data, params) : data.forEach((item => roundTransform(item, params))), 
  params.removeUseless && (data = removeUseless(data)), data.length ? item.attributes[attrName] = js2transform(data, params) : delete item.attributes[attrName];
}, definePrecision = (data, {...newParams}) => {
  const matrixData = [];
  for (const item of data) "matrix" == item.name && matrixData.push(...item.data.slice(0, 4));
  let significantDigits = newParams.transformPrecision;
  return matrixData.length && (newParams.transformPrecision = Math.min(newParams.transformPrecision, Math.max.apply(Math, matrixData.map(floatDigits)) || newParams.transformPrecision), 
  significantDigits = Math.max.apply(Math, matrixData.map((n => n.toString().replace(/\D+/g, "").length)))), 
  null == newParams.degPrecision && (newParams.degPrecision = Math.max(0, Math.min(newParams.floatPrecision, significantDigits - 2))), 
  newParams;
}, degRound = (data, params) => null != params.degPrecision && params.degPrecision >= 1 && params.floatPrecision < 20 ? smartRound(params.degPrecision, data) : round(data), floatRound = (data, params) => params.floatPrecision >= 1 && params.floatPrecision < 20 ? smartRound(params.floatPrecision, data) : round(data), transformRound = (data, params) => params.transformPrecision >= 1 && params.floatPrecision < 20 ? smartRound(params.transformPrecision, data) : round(data), floatDigits = n => {
  const str = n.toString();
  return str.slice(str.indexOf(".")).length - 1;
}, convertToShorts = (transforms, params) => {
  for (var i = 0; i < transforms.length; i++) {
    var transform = transforms[i];
    if (params.matrixToTransform && "matrix" === transform.name) {
      var decomposed = matrixToTransform(transform, params);
      js2transform(decomposed, params).length <= js2transform([ transform ], params).length && transforms.splice(i, 1, ...decomposed), 
      transform = transforms[i];
    }
    roundTransform(transform, params), params.shortTranslate && "translate" === transform.name && 2 === transform.data.length && !transform.data[1] && transform.data.pop(), 
    params.shortScale && "scale" === transform.name && 2 === transform.data.length && transform.data[0] === transform.data[1] && transform.data.pop(), 
    params.shortRotate && transforms[i - 2] && "translate" === transforms[i - 2].name && "rotate" === transforms[i - 1].name && "translate" === transforms[i].name && transforms[i - 2].data[0] === -transforms[i].data[0] && transforms[i - 2].data[1] === -transforms[i].data[1] && (transforms.splice(i - 2, 3, {
      name: "rotate",
      data: [ transforms[i - 1].data[0], transforms[i - 2].data[0], transforms[i - 2].data[1] ]
    }), i -= 2);
  }
  return transforms;
}, removeUseless = transforms => transforms.filter((transform => !([ "translate", "rotate", "skewX", "skewY" ].indexOf(transform.name) > -1 && (1 == transform.data.length || "rotate" == transform.name) && !transform.data[0] || "translate" == transform.name && !transform.data[0] && !transform.data[1] || "scale" == transform.name && 1 == transform.data[0] && (transform.data.length < 2 || 1 == transform.data[1]) || "matrix" == transform.name && 1 == transform.data[0] && 1 == transform.data[3] && !(transform.data[1] || transform.data[2] || transform.data[4] || transform.data[5])))), js2transform = (transformJS, params) => {
  var transformString = "";
  return transformJS.forEach((transform => {
    roundTransform(transform, params), transformString += (transformString && " ") + transform.name + "(" + cleanupOutData(transform.data, params) + ")";
  })), transformString;
}, roundTransform = (transform, params) => {
  switch (transform.name) {
   case "translate":
    transform.data = floatRound(transform.data, params);
    break;

   case "rotate":
    transform.data = [ ...degRound(transform.data.slice(0, 1), params), ...floatRound(transform.data.slice(1), params) ];
    break;

   case "skewX":
   case "skewY":
    transform.data = degRound(transform.data, params);
    break;

   case "scale":
    transform.data = transformRound(transform.data, params);
    break;

   case "matrix":
    transform.data = [ ...transformRound(transform.data.slice(0, 4), params), ...floatRound(transform.data.slice(4), params) ];
  }
  return transform;
}, round = data => data.map(Math.round), smartRound = (precision, data) => {
  for (var i = data.length, tolerance = +Math.pow(0.1, precision).toFixed(precision); i--; ) if (Number(data[i].toFixed(precision)) !== data[i]) {
    var rounded = +data[i].toFixed(precision - 1);
    data[i] = +Math.abs(rounded - data[i]).toFixed(precision + 1) >= tolerance ? +data[i].toFixed(precision) : rounded;
  }
  return data;
};

var removeEmptyAttrs$2 = {};

const {attrsGroups: attrsGroups$1} = _collections;

removeEmptyAttrs$2.name = "removeEmptyAttrs", removeEmptyAttrs$2.description = "removes empty attributes", 
removeEmptyAttrs$2.fn = () => ({
  element: {
    enter: node => {
      for (const [name, value] of Object.entries(node.attributes)) "" === value && !1 === attrsGroups$1.conditionalProcessing.includes(name) && delete node.attributes[name];
    }
  }
});

var removeEmptyContainers$2 = {};

const {detachNodeFromParent: detachNodeFromParent$8} = xast, {elemsGroups} = _collections;

removeEmptyContainers$2.name = "removeEmptyContainers", removeEmptyContainers$2.description = "removes empty container elements", 
removeEmptyContainers$2.fn = () => ({
  element: {
    exit: (node, parentNode) => {
      "svg" !== node.name && !1 !== elemsGroups.container.includes(node.name) && 0 === node.children.length && ("pattern" === node.name && 0 !== Object.keys(node.attributes).length || "g" === node.name && null != node.attributes.filter || "mask" === node.name && null != node.attributes.id || detachNodeFromParent$8(node, parentNode));
    }
  }
});

var mergePaths$2 = {};

const {detachNodeFromParent: detachNodeFromParent$7} = xast, {collectStylesheet, computeStyle} = style, {path2js, js2path, intersects: intersects$1} = _path;

mergePaths$2.name = "mergePaths", mergePaths$2.description = "merges multiple paths in one if possible", 
mergePaths$2.fn = (root, params) => {
  const {force = !1, floatPrecision, noSpaceAfterFlags = !1} = params, stylesheet = collectStylesheet(root);
  return {
    element: {
      enter: node => {
        let prevChild = null;
        for (const child of node.children) {
          if (null == prevChild || "element" !== prevChild.type || "path" !== prevChild.name || 0 !== prevChild.children.length || null == prevChild.attributes.d) {
            prevChild = child;
            continue;
          }
          if ("element" !== child.type || "path" !== child.name || 0 !== child.children.length || null == child.attributes.d) {
            prevChild = child;
            continue;
          }
          const computedStyle = computeStyle(stylesheet, child);
          if (computedStyle["marker-start"] || computedStyle["marker-mid"] || computedStyle["marker-end"]) {
            prevChild = child;
            continue;
          }
          const prevChildAttrs = Object.keys(prevChild.attributes), childAttrs = Object.keys(child.attributes);
          let attributesAreEqual = prevChildAttrs.length === childAttrs.length;
          for (const name of childAttrs) "d" !== name && (null != prevChild.attributes[name] && prevChild.attributes[name] === child.attributes[name] || (attributesAreEqual = !1));
          const prevPathJS = path2js(prevChild), curPathJS = path2js(child);
          !attributesAreEqual || !force && intersects$1(prevPathJS, curPathJS) ? prevChild = child : (js2path(prevChild, prevPathJS.concat(curPathJS), {
            floatPrecision,
            noSpaceAfterFlags
          }), detachNodeFromParent$7(child, node));
        }
      }
    }
  };
};

var removeUnusedNS$2 = {
  name: "removeUnusedNS",
  description: "removes unused namespaces declaration",
  fn: () => {
    const unusedNamespaces = new Set;
    return {
      element: {
        enter: (node, parentNode) => {
          if ("svg" === node.name && "root" === parentNode.type) for (const name of Object.keys(node.attributes)) if (name.startsWith("xmlns:")) {
            const local = name.slice(6);
            unusedNamespaces.add(local);
          }
          if (0 !== unusedNamespaces.size) {
            if (node.name.includes(":")) {
              const [ns] = node.name.split(":");
              unusedNamespaces.has(ns) && unusedNamespaces.delete(ns);
            }
            for (const name of Object.keys(node.attributes)) if (name.includes(":")) {
              const [ns] = name.split(":");
              unusedNamespaces.delete(ns);
            }
          }
        },
        exit: (node, parentNode) => {
          if ("svg" === node.name && "root" === parentNode.type) for (const name of unusedNamespaces) delete node.attributes[`xmlns:${name}`];
        }
      }
    };
  }
}, sortAttrs$1 = {
  name: "sortAttrs",
  description: "Sort element attributes for better compression",
  fn: (_root, params) => {
    const {order = [ "id", "width", "height", "x", "x1", "x2", "y", "y1", "y2", "cx", "cy", "r", "fill", "stroke", "marker", "d", "points" ], xmlnsOrder = "front"} = params, getNsPriority = name => {
      if ("front" === xmlnsOrder) {
        if ("xmlns" === name) return 3;
        if (name.startsWith("xmlns:")) return 2;
      }
      return name.includes(":") ? 1 : 0;
    }, compareAttrs = ([aName], [bName]) => {
      const aPriority = getNsPriority(aName), priorityNs = getNsPriority(bName) - aPriority;
      if (0 !== priorityNs) return priorityNs;
      const [aPart] = aName.split("-"), [bPart] = bName.split("-");
      if (aPart !== bPart) {
        const aInOrderFlag = order.includes(aPart) ? 1 : 0, bInOrderFlag = order.includes(bPart) ? 1 : 0;
        if (1 === aInOrderFlag && 1 === bInOrderFlag) return order.indexOf(aPart) - order.indexOf(bPart);
        const priorityOrder = bInOrderFlag - aInOrderFlag;
        if (0 !== priorityOrder) return priorityOrder;
      }
      return aName < bName ? -1 : 1;
    };
    return {
      element: {
        enter: node => {
          const attrs = Object.entries(node.attributes);
          attrs.sort(compareAttrs);
          const sortedAttributes = {};
          for (const [name, value] of attrs) sortedAttributes[name] = value;
          node.attributes = sortedAttributes;
        }
      }
    };
  }
}, sortDefsChildren$2 = {
  name: "sortDefsChildren",
  description: "Sorts children of <defs> to improve compression",
  fn: () => ({
    element: {
      enter: node => {
        if ("defs" === node.name) {
          const frequencies = new Map;
          for (const child of node.children) if ("element" === child.type) {
            const frequency = frequencies.get(child.name);
            null == frequency ? frequencies.set(child.name, 1) : frequencies.set(child.name, frequency + 1);
          }
          node.children.sort(((a, b) => {
            if ("element" !== a.type || "element" !== b.type) return 0;
            const aFrequency = frequencies.get(a.name), bFrequency = frequencies.get(b.name);
            if (null != aFrequency && null != bFrequency) {
              const frequencyComparison = bFrequency - aFrequency;
              if (0 !== frequencyComparison) return frequencyComparison;
            }
            const lengthComparison = b.name.length - a.name.length;
            return 0 !== lengthComparison ? lengthComparison : a.name !== b.name ? a.name > b.name ? -1 : 1 : 0;
          }));
        }
      }
    }
  })
}, removeTitle$2 = {};

const {detachNodeFromParent: detachNodeFromParent$6} = xast;

removeTitle$2.name = "removeTitle", removeTitle$2.description = "removes <title>", 
removeTitle$2.fn = () => ({
  element: {
    enter: (node, parentNode) => {
      "title" === node.name && detachNodeFromParent$6(node, parentNode);
    }
  }
});

var removeDesc$2 = {};

const {detachNodeFromParent: detachNodeFromParent$5} = xast;

removeDesc$2.name = "removeDesc", removeDesc$2.description = "removes <desc>";

const standardDescs = /^(Created with|Created using)/;

removeDesc$2.fn = (root, params) => {
  const {removeAny = !0} = params;
  return {
    element: {
      enter: (node, parentNode) => {
        "desc" === node.name && (removeAny || 0 === node.children.length || "text" === node.children[0].type && standardDescs.test(node.children[0].value)) && detachNodeFromParent$5(node, parentNode);
      }
    }
  };
};

const {createPreset: createPreset$1} = tools;

var _default = createPreset$1({
  name: "defaultPreset",
  plugins: [ removeDoctype$2, removeXMLProcInst$2, removeComments$2, removeMetadata$2, removeEditorsNSData$2, cleanupAttrs$2, mergeStyles$2, inlineStyles$2, minifyStyles$1, cleanupIds$2, removeUselessDefs$2, cleanupNumericValues$2, convertColors$2, removeUnknownsAndDefaults$2, removeNonInheritableGroupAttrs$2, removeUselessStrokeAndFill$2, removeViewBox$2, cleanupEnableBackground$2, removeHiddenElems$2, removeEmptyText$2, convertShapeToPath$1, convertEllipseToCircle$2, moveElemsAttrsToGroup$1, moveGroupAttrsToElems$1, collapseGroups$2, convertPathData$2, convertTransform$3, removeEmptyAttrs$2, removeEmptyContainers$2, mergePaths$2, removeUnusedNS$2, sortAttrs$1, sortDefsChildren$2, removeTitle$2, removeDesc$2 ]
});

const {createPreset} = tools;

var safe = createPreset({
  name: "safePreset",
  plugins: [ removeDoctype$2, removeXMLProcInst$2, removeComments$2, removeMetadata$2, removeEditorsNSData$2, cleanupAttrs$2, mergeStyles$2, cleanupIds$2, inlineStyles$2, removeUselessDefs$2, cleanupNumericValues$2, convertColors$2, removeUnknownsAndDefaults$2, removeNonInheritableGroupAttrs$2, removeUselessStrokeAndFill$2, removeViewBox$2, cleanupEnableBackground$2, removeHiddenElems$2, removeEmptyText$2, convertEllipseToCircle$2, collapseGroups$2, convertPathData$2, convertTransform$3, removeEmptyAttrs$2, removeEmptyContainers$2, mergePaths$2, removeUnusedNS$2, sortDefsChildren$2, removeTitle$2, removeDesc$2 ]
}), addAttributesToSVGElement = {
  name: "addAttributesToSVGElement",
  description: "adds attributes to an outer <svg> element"
};

addAttributesToSVGElement.fn = (root, params) => {
  if (!Array.isArray(params.attributes) && !params.attribute) return console.error('Error in plugin "addAttributesToSVGElement": absent parameters.\nIt should have a list of "attributes" or one "attribute".\nConfig example:\n\nplugins: [\n  {\n    name: \'addAttributesToSVGElement\',\n    params: {\n      attribute: "mySvg"\n    }\n  }\n]\n\nplugins: [\n  {\n    name: \'addAttributesToSVGElement\',\n    params: {\n      attributes: ["mySvg", "size-big"]\n    }\n  }\n]\n\nplugins: [\n  {\n    name: \'addAttributesToSVGElement\',\n    params: {\n      attributes: [\n        {\n          focusable: false\n        },\n        {\n          \'data-image\': icon\n        }\n      ]\n    }\n  }\n]\n'), 
  null;
  const attributes = params.attributes || [ params.attribute ];
  return {
    element: {
      enter: (node, parentNode) => {
        if ("svg" === node.name && "root" === parentNode.type) for (const attribute of attributes) if ("string" == typeof attribute && null == node.attributes[attribute] && (node.attributes[attribute] = void 0), 
        "object" == typeof attribute) for (const key of Object.keys(attribute)) null == node.attributes[key] && (node.attributes[key] = attribute[key]);
      }
    }
  };
};

var addClassesToSVGElement = {
  name: "addClassesToSVGElement",
  description: "adds classnames to an outer <svg> element"
};

addClassesToSVGElement.fn = (root, params) => {
  if (!(Array.isArray(params.classNames) && params.classNames.some(String) || params.className)) return console.error('Error in plugin "addClassesToSVGElement": absent parameters.\nIt should have a list of classes in "classNames" or one "className".\nConfig example:\n\nplugins: [\n  {\n    name: "addClassesToSVGElement",\n    params: {\n      className: "mySvg"\n    }\n  }\n]\n\nplugins: [\n  {\n    name: "addClassesToSVGElement",\n    params: {\n      classNames: ["mySvg", "size-big"]\n    }\n  }\n]\n'), 
  null;
  const classNames = params.classNames || [ params.className ];
  return {
    element: {
      enter: (node, parentNode) => {
        if ("svg" === node.name && "root" === parentNode.type) {
          const classList = new Set(null == node.attributes.class ? null : node.attributes.class.split(" "));
          for (const className of classNames) null != className && classList.add(className);
          node.attributes.class = Array.from(classList).join(" ");
        }
      }
    }
  };
};

var cleanupListOfValues = {};

const {removeLeadingZero} = tools;

cleanupListOfValues.name = "cleanupListOfValues", cleanupListOfValues.description = "rounds list of values to the fixed precision";

const regNumericValues = /^([-+]?\d*\.?\d+([eE][-+]?\d+)?)(px|pt|pc|mm|cm|m|in|ft|em|ex|%)?$/, regSeparator = /\s+,?\s*|,\s*/, absoluteLengths = {
  cm: 96 / 2.54,
  mm: 96 / 25.4,
  in: 96,
  pt: 4 / 3,
  pc: 16,
  px: 1
};

cleanupListOfValues.fn = (_root, params) => {
  const {floatPrecision = 3, leadingZero = !0, defaultPx = !0, convertToPx = !0} = params, roundValues = lists => {
    const roundedList = [];
    for (const elem of lists.split(regSeparator)) {
      const match = elem.match(regNumericValues), matchNew = elem.match(/new/);
      if (match) {
        let str, num = Number(Number(match[1]).toFixed(floatPrecision)), units = match[3] || "";
        if (convertToPx && units && units in absoluteLengths) {
          const pxNum = Number((absoluteLengths[units] * Number(match[1])).toFixed(floatPrecision));
          pxNum.toString().length < match[0].length && (num = pxNum, units = "px");
        }
        str = leadingZero ? removeLeadingZero(num) : num.toString(), defaultPx && "px" === units && (units = ""), 
        roundedList.push(str + units);
      } else matchNew ? roundedList.push("new") : elem && roundedList.push(elem);
    }
    return roundedList.join(" ");
  };
  return {
    element: {
      enter: node => {
        null != node.attributes.points && (node.attributes.points = roundValues(node.attributes.points)), 
        null != node.attributes["enable-background"] && (node.attributes["enable-background"] = roundValues(node.attributes["enable-background"])), 
        null != node.attributes.viewBox && (node.attributes.viewBox = roundValues(node.attributes.viewBox)), 
        null != node.attributes["stroke-dasharray"] && (node.attributes["stroke-dasharray"] = roundValues(node.attributes["stroke-dasharray"])), 
        null != node.attributes.dx && (node.attributes.dx = roundValues(node.attributes.dx)), 
        null != node.attributes.dy && (node.attributes.dy = roundValues(node.attributes.dy)), 
        null != node.attributes.x && (node.attributes.x = roundValues(node.attributes.x)), 
        null != node.attributes.y && (node.attributes.y = roundValues(node.attributes.y));
      }
    }
  };
};

var convertStyleToAttrs = {};

const {attrsGroups} = _collections;

convertStyleToAttrs.name = "convertStyleToAttrs", convertStyleToAttrs.description = "converts style to attributes";

const g = (...args) => "(?:" + args.join("|") + ")", stylingProps = attrsGroups.presentation, rEscape = "\\\\(?:[0-9a-f]{1,6}\\s?|\\r\\n|.)", rAttr = "\\s*(" + g("[^:;\\\\]", rEscape) + "*?)\\s*", rSingleQuotes = "'(?:[^'\\n\\r\\\\]|" + rEscape + ")*?(?:'|$)", rQuotes = '"(?:[^"\\n\\r\\\\]|' + rEscape + ')*?(?:"|$)', rQuotedString = new RegExp("^" + g(rSingleQuotes, rQuotes) + "$"), rParenthesis = "\\(" + g("[^'\"()\\\\]+", rEscape, rSingleQuotes, rQuotes) + "*?\\)", rValue = "\\s*(" + g("[^!'\"();\\\\]+?", rEscape, rSingleQuotes, rQuotes, rParenthesis, "[^;]*?") + "*?)", regDeclarationBlock = new RegExp(rAttr + ":" + rValue + "(\\s*!important(?![-(\\w]))?\\s*(?:;\\s*|$)", "ig"), regStripComments = new RegExp(g(rEscape, rSingleQuotes, rQuotes, "/\\*[^]*?\\*/"), "ig");

convertStyleToAttrs.fn = (_root, params) => {
  const {keepImportant = !1} = params;
  return {
    element: {
      enter: node => {
        if (null != node.attributes.style) {
          let styles = [];
          const newAttributes = {}, styleValue = node.attributes.style.replace(regStripComments, (match => "/" == match[0] ? "" : "\\" == match[0] && /[-g-z]/i.test(match[1]) ? match[1] : match));
          regDeclarationBlock.lastIndex = 0;
          for (var rule; rule = regDeclarationBlock.exec(styleValue); ) keepImportant && rule[3] || styles.push([ rule[1], rule[2] ]);
          styles.length && (styles = styles.filter((function(style) {
            if (style[0]) {
              var prop = style[0].toLowerCase(), val = style[1];
              if (rQuotedString.test(val) && (val = val.slice(1, -1)), stylingProps.includes(prop)) return newAttributes[prop] = val, 
              !1;
            }
            return !0;
          })), Object.assign(node.attributes, newAttributes), styles.length ? node.attributes.style = styles.map((declaration => declaration.join(":"))).join(";") : delete node.attributes.style);
        }
      }
    }
  };
};

var prefixIds = {};

const csstree = cjs$2, {referencesProps} = _collections;

prefixIds.name = "prefixIds", prefixIds.description = "prefix IDs";

const prefixId = (prefix, value) => value.startsWith(prefix) ? value : prefix + value, prefixReference = (prefix, value) => value.startsWith("#") ? "#" + prefixId(prefix, value.slice(1)) : null;

prefixIds.fn = (_root, params, info) => {
  const {delim = "__", prefixIds = !0, prefixClassNames = !0} = params;
  return {
    element: {
      enter: node => {
        let prefix = "prefix" + delim;
        var str;
        if ("function" == typeof params.prefix ? prefix = params.prefix(node, info) + delim : "string" == typeof params.prefix ? prefix = params.prefix + delim : !1 === params.prefix ? prefix = "" : null != info.path && info.path.length > 0 && (str = (path => {
          const matched = path.match(/[/\\]?([^/\\]+)$/);
          return matched ? matched[1] : "";
        })(info.path), prefix = str.replace(/[. ]/g, "_") + delim), "style" === node.name) {
          if (0 === node.children.length) return;
          let cssText = "";
          "text" !== node.children[0].type && "cdata" !== node.children[0].type || (cssText = node.children[0].value);
          let cssAst = null;
          try {
            cssAst = csstree.parse(cssText, {
              parseValue: !0,
              parseCustomProperty: !1
            });
          } catch {
            return;
          }
          return csstree.walk(cssAst, (node => {
            if (prefixIds && "IdSelector" === node.type || prefixClassNames && "ClassSelector" === node.type) node.name = prefixId(prefix, node.name); else if ("Url" === node.type && node.value.length > 0) {
              const prefixed = prefixReference(prefix, (string => string.startsWith('"') && string.endsWith('"') || string.startsWith("'") && string.endsWith("'") ? string.slice(1, -1) : string)(node.value));
              null != prefixed && (node.value = prefixed);
            }
          })), void ("text" !== node.children[0].type && "cdata" !== node.children[0].type || (node.children[0].value = csstree.generate(cssAst)));
        }
        prefixIds && null != node.attributes.id && 0 !== node.attributes.id.length && (node.attributes.id = prefixId(prefix, node.attributes.id)), 
        prefixClassNames && null != node.attributes.class && 0 !== node.attributes.class.length && (node.attributes.class = node.attributes.class.split(/\s+/).map((name => prefixId(prefix, name))).join(" "));
        for (const name of [ "href", "xlink:href" ]) if (null != node.attributes[name] && 0 !== node.attributes[name].length) {
          const prefixed = prefixReference(prefix, node.attributes[name]);
          null != prefixed && (node.attributes[name] = prefixed);
        }
        for (const name of referencesProps) null != node.attributes[name] && 0 !== node.attributes[name].length && (node.attributes[name] = node.attributes[name].replace(/url\((.*?)\)/gi, ((match, url) => {
          const prefixed = prefixReference(prefix, url);
          return null == prefixed ? match : `url(${prefixed})`;
        })));
        for (const name of [ "begin", "end" ]) if (null != node.attributes[name] && 0 !== node.attributes[name].length) {
          const parts = node.attributes[name].split(/\s*;\s+/).map((val => {
            if (val.endsWith(".end") || val.endsWith(".start")) {
              const [id, postfix] = val.split(".");
              return `${prefixId(prefix, id)}.${postfix}`;
            }
            return val;
          }));
          node.attributes[name] = parts.join("; ");
        }
      }
    }
  };
};

var removeAttributesBySelector = {};

const {querySelectorAll} = xast;

removeAttributesBySelector.name = "removeAttributesBySelector", removeAttributesBySelector.description = "removes attributes of elements that match a css selector", 
removeAttributesBySelector.fn = (root, params) => {
  const selectors = Array.isArray(params.selectors) ? params.selectors : [ params ];
  for (const {selector, attributes} of selectors) {
    const nodes = querySelectorAll(root, selector);
    for (const node of nodes) if ("element" === node.type) if (Array.isArray(attributes)) for (const name of attributes) delete node.attributes[name]; else delete node.attributes[attributes];
  }
  return {};
};

var removeAttrs = {
  name: "removeAttrs",
  description: "removes specified attributes"
};

removeAttrs.fn = (root, params) => {
  if (void 0 === params.attrs) return null;
  const elemSeparator = "string" == typeof params.elemSeparator ? params.elemSeparator : ":", preserveCurrentColor = "boolean" == typeof params.preserveCurrentColor && params.preserveCurrentColor, attrs = Array.isArray(params.attrs) ? params.attrs : [ params.attrs ];
  return {
    element: {
      enter: node => {
        for (let pattern of attrs) {
          !1 === pattern.includes(elemSeparator) ? pattern = [ ".*", elemSeparator, pattern, elemSeparator, ".*" ].join("") : pattern.split(elemSeparator).length < 3 && (pattern = [ pattern, elemSeparator, ".*" ].join(""));
          const list = pattern.split(elemSeparator).map((value => ("*" === value && (value = ".*"), 
          new RegExp([ "^", value, "$" ].join(""), "i"))));
          if (list[0].test(node.name)) for (const [name, value] of Object.entries(node.attributes)) {
            !(preserveCurrentColor && "fill" == name && "currentColor" == value) && !(preserveCurrentColor && "stroke" == name && "currentColor" == value) && list[1].test(name) && list[2].test(value) && delete node.attributes[name];
          }
        }
      }
    }
  };
};

var removeDimensions = {
  name: "removeDimensions",
  description: "removes width and height in presence of viewBox (opposite to removeViewBox, disable it first)",
  fn: () => ({
    element: {
      enter: node => {
        if ("svg" === node.name) if (null != node.attributes.viewBox) delete node.attributes.width, 
        delete node.attributes.height; else if (null != node.attributes.width && null != node.attributes.height && !1 === Number.isNaN(Number(node.attributes.width)) && !1 === Number.isNaN(Number(node.attributes.height))) {
          const width = Number(node.attributes.width), height = Number(node.attributes.height);
          node.attributes.viewBox = `0 0 ${width} ${height}`, delete node.attributes.width, 
          delete node.attributes.height;
        }
      }
    }
  })
}, removeElementsByAttr = {};

const {detachNodeFromParent: detachNodeFromParent$4} = xast;

removeElementsByAttr.name = "removeElementsByAttr", removeElementsByAttr.description = "removes arbitrary elements by ID or className (disabled by default)", 
removeElementsByAttr.fn = (root, params) => {
  const ids = null == params.id ? [] : Array.isArray(params.id) ? params.id : [ params.id ], classes = null == params.class ? [] : Array.isArray(params.class) ? params.class : [ params.class ];
  return {
    element: {
      enter: (node, parentNode) => {
        if (null != node.attributes.id && 0 !== ids.length && ids.includes(node.attributes.id) && detachNodeFromParent$4(node, parentNode), 
        node.attributes.class && 0 !== classes.length) {
          const classList = node.attributes.class.split(" ");
          for (const item of classes) if (classList.includes(item)) {
            detachNodeFromParent$4(node, parentNode);
            break;
          }
        }
      }
    }
  };
};

var removeOffCanvasPaths = {};

const {visitSkip, detachNodeFromParent: detachNodeFromParent$3} = xast, {parsePathData} = path, {intersects} = _path;

removeOffCanvasPaths.name = "removeOffCanvasPaths", removeOffCanvasPaths.description = "removes elements that are drawn outside of the viewbox (disabled by default)", 
removeOffCanvasPaths.fn = () => {
  let viewBoxData = null;
  return {
    element: {
      enter: (node, parentNode) => {
        if ("svg" === node.name && "root" === parentNode.type) {
          let viewBox = "";
          null != node.attributes.viewBox ? viewBox = node.attributes.viewBox : null != node.attributes.height && null != node.attributes.width && (viewBox = `0 0 ${node.attributes.width} ${node.attributes.height}`), 
          viewBox = viewBox.replace(/[,+]|px/g, " ").replace(/\s+/g, " ").replace(/^\s*|\s*$/g, "");
          const m = /^(-?\d*\.?\d+) (-?\d*\.?\d+) (\d*\.?\d+) (\d*\.?\d+)$/.exec(viewBox);
          if (null == m) return;
          const left = Number.parseFloat(m[1]), top = Number.parseFloat(m[2]), width = Number.parseFloat(m[3]), height = Number.parseFloat(m[4]);
          viewBoxData = {
            left,
            top,
            right: left + width,
            bottom: top + height,
            width,
            height
          };
        }
        if (null != node.attributes.transform) return visitSkip;
        if ("path" === node.name && null != node.attributes.d && null != viewBoxData) {
          const pathData = parsePathData(node.attributes.d);
          let visible = !1;
          for (const pathDataItem of pathData) if ("M" === pathDataItem.command) {
            const [x, y] = pathDataItem.args;
            x >= viewBoxData.left && x <= viewBoxData.right && y >= viewBoxData.top && y <= viewBoxData.bottom && (visible = !0);
          }
          if (visible) return;
          2 === pathData.length && pathData.push({
            command: "z",
            args: []
          });
          const {left, top, width, height} = viewBoxData;
          !1 === intersects([ {
            command: "M",
            args: [ left, top ]
          }, {
            command: "h",
            args: [ width ]
          }, {
            command: "v",
            args: [ height ]
          }, {
            command: "H",
            args: [ left ]
          }, {
            command: "z",
            args: []
          } ], pathData) && detachNodeFromParent$3(node, parentNode);
        }
      }
    }
  };
};

var removeRasterImages = {};

const {detachNodeFromParent: detachNodeFromParent$2} = xast;

removeRasterImages.name = "removeRasterImages", removeRasterImages.description = "removes raster images (disabled by default)", 
removeRasterImages.fn = () => ({
  element: {
    enter: (node, parentNode) => {
      "image" === node.name && null != node.attributes["xlink:href"] && /(\.|image\/)(jpg|png|gif)/.test(node.attributes["xlink:href"]) && detachNodeFromParent$2(node, parentNode);
    }
  }
});

var removeScriptElement = {};

const {detachNodeFromParent: detachNodeFromParent$1} = xast;

removeScriptElement.name = "removeScriptElement", removeScriptElement.description = "removes <script> elements (disabled by default)", 
removeScriptElement.fn = () => ({
  element: {
    enter: (node, parentNode) => {
      "script" === node.name && detachNodeFromParent$1(node, parentNode);
    }
  }
});

var removeStyleElement = {};

const {detachNodeFromParent} = xast;

removeStyleElement.name = "removeStyleElement", removeStyleElement.description = "removes <style> element (disabled by default)", 
removeStyleElement.fn = () => ({
  element: {
    enter: (node, parentNode) => {
      "style" === node.name && detachNodeFromParent(node, parentNode);
    }
  }
});

var removeXMLNS = {
  name: "removeXMLNS",
  description: "removes xmlns attribute (for inline svg, disabled by default)",
  fn: () => ({
    element: {
      enter: node => {
        "svg" === node.name && (delete node.attributes.xmlns, delete node.attributes["xmlns:xlink"]);
      }
    }
  })
}, reusePaths = {
  name: "reusePaths",
  description: "Finds <path> elements with the same d, fill, and stroke, and converts them to <use> elements referencing a single <path> def.",
  fn: () => {
    const paths = new Map;
    return {
      element: {
        enter: node => {
          if ("path" === node.name && null != node.attributes.d) {
            const d = node.attributes.d, fill = node.attributes.fill || "", key = d + ";s:" + (node.attributes.stroke || "") + ";f:" + fill;
            let list = paths.get(key);
            null == list && (list = [], paths.set(key, list)), list.push(node);
          }
        },
        exit: (node, parentNode) => {
          if ("svg" === node.name && "root" === parentNode.type) {
            const defsTag = {
              type: "element",
              name: "defs",
              attributes: {},
              children: []
            };
            Object.defineProperty(defsTag, "parentNode", {
              writable: !0,
              value: node
            });
            let index = 0;
            for (const list of paths.values()) if (list.length > 1) {
              const reusablePath = {
                type: "element",
                name: "path",
                attributes: {
                  ...list[0].attributes
                },
                children: []
              };
              let id;
              delete reusablePath.attributes.transform, null == reusablePath.attributes.id ? (id = "reuse-" + index, 
              index += 1, reusablePath.attributes.id = id) : (id = reusablePath.attributes.id, 
              delete list[0].attributes.id), Object.defineProperty(reusablePath, "parentNode", {
                writable: !0,
                value: defsTag
              }), defsTag.children.push(reusablePath);
              for (const pathNode of list) pathNode.name = "use", pathNode.attributes["xlink:href"] = "#" + id, 
              delete pathNode.attributes.d, delete pathNode.attributes.stroke, delete pathNode.attributes.fill;
            }
            0 !== defsTag.children.length && (null == node.attributes["xmlns:xlink"] && (node.attributes["xmlns:xlink"] = "http://www.w3.org/1999/xlink"), 
            node.children.unshift(defsTag));
          }
        }
      }
    };
  }
};

builtin$1.builtin = [ _default, safe, addAttributesToSVGElement, addClassesToSVGElement, cleanupAttrs$2, cleanupEnableBackground$2, cleanupIds$2, cleanupListOfValues, cleanupNumericValues$2, collapseGroups$2, convertColors$2, convertEllipseToCircle$2, convertPathData$2, convertShapeToPath$1, convertStyleToAttrs, convertTransform$3, mergeStyles$2, inlineStyles$2, mergePaths$2, minifyStyles$1, moveElemsAttrsToGroup$1, moveGroupAttrsToElems$1, prefixIds, removeAttributesBySelector, removeAttrs, removeComments$2, removeDesc$2, removeDimensions, removeDoctype$2, removeEditorsNSData$2, removeElementsByAttr, removeEmptyAttrs$2, removeEmptyContainers$2, removeEmptyText$2, removeHiddenElems$2, removeMetadata$2, removeNonInheritableGroupAttrs$2, removeOffCanvasPaths, removeRasterImages, removeScriptElement, removeStyleElement, removeTitle$2, removeUnknownsAndDefaults$2, removeUnusedNS$2, removeUselessDefs$2, removeUselessStrokeAndFill$2, removeViewBox$2, removeXMLNS, removeXMLProcInst$2, reusePaths, sortAttrs$1, sortDefsChildren$2 ];

var freeGlobal = "object" == typeof commonjsGlobal && commonjsGlobal && commonjsGlobal.Object === Object && commonjsGlobal, freeSelf = "object" == typeof self && self && self.Object === Object && self, _Symbol = (freeGlobal || freeSelf || Function("return this")()).Symbol, Symbol$2 = _Symbol, objectProto$2 = Object.prototype, hasOwnProperty$2 = objectProto$2.hasOwnProperty, nativeObjectToString$1 = objectProto$2.toString, symToStringTag$1 = Symbol$2 ? Symbol$2.toStringTag : void 0;

var _getRawTag = function(value) {
  var isOwn = hasOwnProperty$2.call(value, symToStringTag$1), tag = value[symToStringTag$1];
  try {
    value[symToStringTag$1] = void 0;
    var unmasked = !0;
  } catch (e) {}
  var result = nativeObjectToString$1.call(value);
  return unmasked && (isOwn ? value[symToStringTag$1] = tag : delete value[symToStringTag$1]), 
  result;
}, nativeObjectToString = Object.prototype.toString;

var _objectToString = function(value) {
  return nativeObjectToString.call(value);
}, getRawTag = _getRawTag, objectToString = _objectToString, symToStringTag = _Symbol ? _Symbol.toStringTag : void 0;

var _baseGetTag = function(value) {
  return null == value ? void 0 === value ? "[object Undefined]" : "[object Null]" : symToStringTag && symToStringTag in Object(value) ? getRawTag(value) : objectToString(value);
};

var getPrototype$1 = function(func, transform) {
  return function(arg) {
    return func(transform(arg));
  };
}(Object.getPrototypeOf, Object);

var isObjectLike_1 = function(value) {
  return null != value && "object" == typeof value;
}, baseGetTag$1 = _baseGetTag, getPrototype = getPrototype$1, isObjectLike$1 = isObjectLike_1, funcProto = Function.prototype, objectProto = Object.prototype, funcToString = funcProto.toString, hasOwnProperty$1 = objectProto.hasOwnProperty, objectCtorString = funcToString.call(Object);

var isPlainObject_1 = function(value) {
  if (!isObjectLike$1(value) || "[object Object]" != baseGetTag$1(value)) return !1;
  var proto = getPrototype(value);
  if (null === proto) return !0;
  var Ctor = hasOwnProperty$1.call(proto, "constructor") && proto.constructor;
  return "function" == typeof Ctor && Ctor instanceof Ctor && funcToString.call(Ctor) == objectCtorString;
}, isArray$1 = Array.isArray, baseGetTag = _baseGetTag, isArray = isArray$1, isObjectLike = isObjectLike_1;

var isString_1 = function(value) {
  return "string" == typeof value || !isArray(value) && isObjectLike(value) && "[object String]" == baseGetTag(value);
};

const {builtin} = builtin$1, isPlainObject$1 = isPlainObject_1, isString = isString_1, pluginsMap = {};

for (const plugin of builtin) pluginsMap[plugin.name] = plugin;

plugins.resolvePlugin = function(plugin) {
  if ("string" == typeof plugin) {
    const builtinPlugin = pluginsMap[plugin];
    if (null == builtinPlugin) throw Error(`Unknown builtin plugin "${plugin}" specified.`);
    return {
      name: plugin,
      params: {},
      fn: builtinPlugin.fn
    };
  }
  if (isPlainObject$1(plugin)) {
    if (!isString(plugin.name)) throw Error("Plugin name should be specified");
    let fn = plugin.fn;
    if (null == fn) {
      const builtinPlugin = pluginsMap[plugin.name];
      if (null == builtinPlugin) throw Error(`Unknown builtin plugin "${plugin.name}" specified.`);
      fn = builtinPlugin.fn;
    }
    return {
      name: plugin.name,
      params: plugin.params,
      fn
    };
  }
  return null;
};

const {parseSvg} = parser$4, {stringifySvg} = stringifier, {resolvePlugin} = plugins, {encodeSVGDatauri, invokePlugins} = tools, isPlainObject = isPlainObject_1;

var optimize_1 = svgo.optimize = (input, config) => {
  if (null === config && (config = {}), !isPlainObject(config)) throw Error("Config should be an object");
  let plugins = config.plugins || [ "safePreset" ];
  if (!1 === Array.isArray(plugins)) throw Error("Invalid plugins list");
  plugins = plugins.map(resolvePlugin);
  const globalOverrides = {};
  null !== config.floatPrecision && (globalOverrides.floatPrecision = config.floatPrecision);
  let maxPassCount = config.multipass ? 10 : 1, prevResultSize = Number.POSITIVE_INFINITY, output = "", info = {};
  for (let i = 0; i < maxPassCount; i += 1) {
    info.multipassCount = i;
    const ast = parseSvg(input, config.path);
    if (invokePlugins(ast, info, plugins, null, globalOverrides), output = stringifySvg(ast, config), 
    !(output.length < prevResultSize)) break;
    input = output, prevResultSize = output.length;
  }
  return config.datauri && (output = encodeSVGDatauri(output, config.datauri)), output;
};

export { svgo as default, optimize_1 as optimize };
