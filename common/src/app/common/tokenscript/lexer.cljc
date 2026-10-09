;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.lexer
  "Tokenizer for tokenscript source text.

  The lexer is a pure function over a small state map so the parser can
  peek ahead without side effects:

    (let [[token state'] (next-token (make-lexer \"1 + 2\"))] ...)

  Tokens are maps `{:type :number :value \"1\" :line 1 :pos 0 :end-pos 1}`.
  Positions are UTF-16 code unit offsets into the source, matching the
  other tokenscript implementations."
  (:require
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.util :as u]
   [clojure.string :as str]))

;; Vocabulary -----------------------------------------------------------------

(def operations
  {:subtract "-" :add "+" :multiply "*" :divide "/" :power "^"
   :logic-and "&&" :logic-or "||" :logic-not "!"})

(def supported-formats
  "Unit keywords (`SupportedFormats` in the TS implementation)."
  ["px" "em" "rem" "vw" "vh" "pt" "in" "cm" "mm" "deg" "%" "s" "ms" "ch"])

(def supported-format-set (set supported-formats))

(def reserved-keywords
  #{"true" "false" "null" "undefined" "while" "if" "else" "elif" "return" "variable" "for"})

(def script-only-statement-keywords
  "Keywords that introduce statements, valid in script mode only."
  #{"variable" "if" "while" "for" "return"})

(def uninterpreted-keywords
  ["inside" "outside" "above" "below" "left" "right" "top" "bottom" "before"
   "after" "between" "uppercase" "lowercase" "underline" "none" "innerShadow"
   "outerShadow" "shadow"])

(def uninterpreted-keyword-set (set uninterpreted-keywords))

(def token-type-names
  "Cross runtime names of the token types, used in error messages."
  {:reference "REFERENCE" :number "NUMBER" :operation "OPERATION" :format "FORMAT"
   :lparen "LPAREN" :rparen "RPAREN" :eof "EOF" :comma "COMMA" :hex-color "HEX_COLOR"
   :string "STRING" :explicit-string "EXPLICIT_STRING" :template-string "TEMPLATE_STRING"
   :assign "ASSIGN" :is-eq "IS_EQ" :is-gt "GT" :is-lt "LT" :is-gt-eq "IS_GT_EQ"
   :is-lt-eq "IS_LT_EQ" :is-not-eq "IS_NOT_EQ" :reserved-keyword "RESERVED_KEYWORD"
   :semicolon "SEMICOLON" :logic-not "LOGIC_NOT" :colon "COLON" :dot "DOT"
   :logic-and "LOGIC_AND" :logic-or "LOGIC_OR" :lblock "LBLOCK" :rblock "RBLOCK"
   :partial-reference "PARTIAL_REFERENCE" :partial-string "PARTIAL_STRING"})

(defn token-type-name
  [type]
  (get token-type-names type (str type)))

;; State ----------------------------------------------------------------------

;; A record rather than a map: the lexer reads and updates the state for
;; every token.
(defrecord LexerState [text pos line column last-unitable tolerant? greedy-strings?])

(defn make-lexer
  "Creates the lexer state for `text`. Options:

    :tolerant?       return partial tokens instead of raising on
                     incomplete input
    :greedy-strings? unquoted strings consume everything up to whitespace
                     or a structural delimiter (inline mode)"
  ([text] (make-lexer text nil))
  ([text {:keys [tolerant? greedy-strings?]}]
   (->LexerState (or text "") 0 1 1 nil (boolean tolerant?) (boolean greedy-strings?))))

(defn- char-of
  "One char string at `i` of `text`, nil out of range."
  [^String text i]
  (when (< -1 i (count text))
    (subs text i (inc i))))

(defn- ch=
  "Equality of two one char strings (or nil). JavaScript compares strings
  by value with `identical?`, which skips the protocol dispatch of `=`."
  [a b]
  #?(:clj (= a b)
     :cljs (identical? a b)))

(defn- char-at
  [st i]
  (char-of (:text st) i))

(defn- current
  [st]
  (char-at st (:pos st)))

(defn- peek-char
  ([st] (peek-char st 1))
  ([st n] (char-at st (+ (:pos st) n))))

;; Readers scan ahead by index and move the state once per token with
;; `advance-to`, instead of building a new state for every char.

(defn- advance-to
  "State moved to position `end`, counting lines and columns."
  [st end]
  (let [text (:text st)]
    (loop [i (:pos st) line (:line st) column (:column st)]
      (if (< i end)
        (if (ch= "\n" (char-of text i))
          (recur (inc i) (inc line) 1)
          (recur (inc i) line (inc column)))
        ;; One new record instead of three with a variadic assoc
        (->LexerState text end line column (:last-unitable st) (:tolerant? st) (:greedy-strings? st))))))

(defn- advance
  [st]
  (advance-to st (inc (:pos st))))

(defn- lex-error
  [st code data]
  (err/raise code {:line (:line st) :data data}))

(defn- eat
  [st c]
  (if (= (current st) c)
    (advance st)
    (lex-error st :lexer/expected-character {:expected c :got (current st)})))

(defn- skip-whitespace
  [st]
  (let [text (:text st)
        start (:pos st)
        end (loop [i start] (if (u/space? (char-of text i)) (recur (inc i)) i))]
    (if (= end start) st (advance-to st end))))

(defn- skip-line
  [st]
  (let [st (loop [st st]
             (let [c (current st)]
               (if (and (some? c) (not= c "\n")) (recur (advance st)) st)))]
    (if (= (current st) "\n") (eat st "\n") st)))

(defn- token
  [type value st start-pos]
  {:type type :value value :line (:line st) :pos start-pos :end-pos (:pos st)})

(defn- digit-start-at?
  "Python like isdigit that also accepts numbers starting with `.`."
  [text i]
  (let [c (char-of text i)]
    (and (some? c)
         (or (u/digit? c)
             (and (ch= c ".") (u/digit? (char-of text (inc i))))))))

(defn- valid-identifier-start?
  [c]
  (when (some? c)
    (or (u/alpha? c)
        (let [cp (u/code-point c)]
          (and (> cp 127)
               (not= cp 180)  ; forward tick
               (not= cp 96)))))) ; backtick

(defn- valid-string-element?
  [c]
  (when (some? c)
    (let [cp (u/code-point c)]
      (or (u/alphanumeric? c) (= cp 45) (= cp 95) (> cp 127)))))

(def ^:private greedy-delimiters
  #{"{" "}" "(" ")" "[" "]" "," "\"" "'" ";"})

(defn- valid-string-element-greedy?
  [c]
  (and (some? c)
       (not (u/space? c))
       (not (contains? greedy-delimiters c))))

(defn- valid-format-position?
  [{:keys [last-unitable]} start-pos]
  (and (some? last-unitable) (= (:end-pos last-unitable) start-pos)))

;; Token readers --------------------------------------------------------------

(defn- read-number
  [st]
  (let [text (:text st)
        start (:pos st)
        initial (if (= (char-of text start) ".") "0" "")
        ;; Every consumed char is part of the number
        text-of (fn [end] (str initial (subs text start end)))
        finish (fn [end]
                 (let [st (advance-to st end)
                       tok (token :number (text-of end) st start)]
                   [tok (assoc st :last-unitable tok)]))]
    (loop [i start has-point? false]
      (let [c (char-of text i)]
        (cond
          (and (some? c) (u/digit? c))
          (recur (inc i) has-point?)

          (= c ".")
          (cond
            ;; A dot not followed by a digit is a method call dot (1.to_string())
            (not (u/digit? (char-of text (inc i))))
            (finish i)

            has-point?
            (lex-error (advance-to st i) :lexer/multiple-decimal-points {:value (str (text-of i) c)})

            :else
            (recur (inc i) true))

          :else
          (finish i))))))

(defn- classify-word
  "Returns the keyword/format/string token for an identifier-like word."
  [st result start simple?]
  (let [normalized (str/lower-case result)]
    (cond
      (and simple? (contains? reserved-keywords normalized))
      (token :reserved-keyword normalized st start)

      (and simple? (contains? supported-format-set normalized) (valid-format-position? st start))
      (token :format normalized st start)

      :else
      (token :string result st start))))

(defn- read-word
  "Reads an unquoted identifier/string element."
  [st]
  (let [start (:pos st)
        greedy? (and (:greedy-strings? st) (not (valid-format-position? st start)))
        check (if greedy? valid-string-element-greedy? valid-string-element?)]
    (loop [i start simple? true]
      (let [c (char-of (:text st) i)]
        (if (check c)
          (recur (inc i)
                 (and simple? (or (u/alphanumeric? c) (= c "-") (= c "_"))))
          (let [st (advance-to st i)]
            [(classify-word st (subs (:text st) start i) start simple?) st]))))))

(defn- read-reference
  [st]
  (let [st (eat st "{")
        text (:text st)
        ref-start (:pos st)
        ;; The name is the consumed text without whitespace
        text-of (fn [end spaces?]
                  (let [raw (subs text ref-start end)]
                    (if spaces?
                      (->> raw (map str) (remove u/space?) (apply str))
                      raw)))]
    (loop [i ref-start spaces? false]
      (let [c (char-of text i)]
        (cond
          (and (ch= c "{") (:tolerant? st))
          (let [st (advance-to st i)]
            [(token :partial-reference (text-of i spaces?) st ref-start) st])

          (ch= c "{")
          (lex-error (advance-to st i) :lexer/unterminated-reference {})

          (nil? c)
          (let [st (advance-to st i)]
            (if (:tolerant? st)
              [(token :partial-reference (text-of i spaces?) st ref-start) st]
              (lex-error st :lexer/unterminated-reference {})))

          (ch= c "}")
          (let [st (advance-to st i)
                result (text-of i spaces?)]
            (if (= result "")
              (if (:tolerant? st)
                [(token :partial-reference "" st ref-start) st]
                (lex-error st :lexer/empty-variable-name {}))
              (let [st' (eat st "}")
                    tok {:type :reference :value result :line (:line st')
                         :pos ref-start :end-pos i}]
                ;; Format adjacency uses the position after the closing brace
                [tok (assoc st' :last-unitable (assoc tok :end-pos (:pos st')))])))

          (u/space? c)
          (recur (inc i) true)

          :else
          (recur (inc i) spaces?))))))

(defn- read-explicit-string
  [st quote]
  (let [start (:pos st)
        st (eat st quote)]
    (loop [st st result ""]
      (let [c (current st)]
        (cond
          (nil? c)
          (if (:tolerant? st)
            [(token :partial-string result st start) st]
            (lex-error st :lexer/unterminated-string {:quote-type quote}))

          (= c quote)
          (let [st (eat st quote)]
            [(token :explicit-string result st start) st])

          :else
          (recur (advance st) (str result c)))))))

(defn- read-template-string
  [st]
  (let [start (:pos st)
        st (eat st "`")]
    (loop [st st result ""]
      (let [c (current st)]
        (cond
          (nil? c)
          (if (:tolerant? st)
            [(token :template-string result st start) st]
            (lex-error st :lexer/unterminated-template-string {}))

          (= c "`")
          (let [st (eat st "`")]
            [(token :template-string result st start) st])

          (and (= c "\\") (contains? #{"{" "$" "`" "\\"} (peek-char st)))
          (recur (-> st advance advance) (str result "\\" (peek-char st)))

          :else
          (recur (advance st) (str result c)))))))

(defn- read-hex-color
  [st]
  (let [text (:text st)
        start (:pos st)
        end (loop [i start]
              (let [c (char-of text i)]
                (if (and (some? c) (or (u/alpha? c) (digit-start-at? text i) (= c "#")))
                  (recur (inc i))
                  i)))
        result (subs text start end)
        st (advance-to st end)]
    (if (contains? #{4 5 7 9} (count result))
      [(token :hex-color result st start) st]
      (if (:tolerant? st)
        [(token :hex-color result st start) st]
        (lex-error st :lexer/invalid-hex-color-format
                   {:value result :expected-length "#RGB, #RGBA, #RRGGBB, or #RRGGBBAA"})))))

(defn- simple-token
  "Consumes `s` and returns a token of `type` with `value`."
  ([st type value s] (simple-token st type value s false))
  ([st type value s unitable?]
   ;; The caller has checked that the input starts with `s`
   (let [start (:pos st)
         st (advance-to st (+ start (count s)))
         tok (token type value st start)]
     [tok (if unitable? (assoc st :last-unitable tok) st)])))

(defn next-token
  "Returns `[token state']`. Raises on invalid input unless the lexer is
  tolerant, in which case invalid characters are skipped and partial
  tokens are produced."
  [st]
  (let [st (skip-whitespace st)
        text (:text st)
        pos (:pos st)
        c (char-of text pos)
        p (char-of text (inc pos))]
    (cond
      (nil? c)
      [(token :eof nil st pos) st]

      (and (ch= c "/") (ch= p "/"))
      (recur (skip-line st))

      (digit-start-at? text pos) (read-number st)
      (or (ch= c "'") (ch= c "\"")) (read-explicit-string st c)
      (ch= c "`") (read-template-string st)
      (valid-identifier-start? c) (read-word st)
      (ch= c "{") (read-reference st)

      :else
      (case c
        "[" (simple-token st :lblock "[" "[")
        "]" (simple-token st :rblock "]" "]")
        "!" (if (ch= p "=")
              (simple-token st :is-not-eq "!=" "!=")
              (simple-token st :operation "!" "!"))
        "+" (simple-token st :operation "+" "+")
        "-" (simple-token st :operation "-" "-")
        "*" (simple-token st :operation "*" "*")
        "/" (simple-token st :operation "/" "/")
        "^" (simple-token st :operation "^" "^")
        "(" (simple-token st :lparen "(" "(")
        ")" (simple-token st :rparen ")" ")" true)
        "," (simple-token st :comma "," ",")
        "." (simple-token st :dot "." ".")
        "#" (read-hex-color st)
        "%" (simple-token st :format "%" "%")
        "=" (if (ch= p "=")
              (simple-token st :is-eq "==" "==")
              (simple-token st :assign "=" "="))
        ">" (if (ch= p "=")
              (simple-token st :is-gt-eq ">=" ">=")
              (simple-token st :is-gt ">" ">"))
        "<" (if (ch= p "=")
              (simple-token st :is-lt-eq "<=" "<=")
              (simple-token st :is-lt "<" "<"))
        ";" (simple-token st :semicolon ";" ";")
        ":" (simple-token st :colon ":" ":")
        (cond
          (and (ch= c "&") (ch= p "&")) (simple-token st :logic-and "&&" "&&")
          (and (ch= c "|") (ch= p "|")) (simple-token st :logic-or "||" "||")
          (:tolerant? st) (recur (advance st))
          :else (lex-error st :lexer/invalid-character {:char c :position pos}))))))

(defn tokenize
  "Tokenizes the whole input, returning a vector of tokens including the
  final `:eof` token."
  ([text] (tokenize text nil))
  ([text opts]
   (loop [st (make-lexer text opts) acc (transient [])]
     (let [[tok st] (next-token st)
           acc (conj! acc tok)]
       (if (= :eof (:type tok))
         (persistent! acc)
         (recur st acc))))))

(defn source-info
  [{:keys [text pos line column]}]
  {:text text :pos pos :line line :column column})
