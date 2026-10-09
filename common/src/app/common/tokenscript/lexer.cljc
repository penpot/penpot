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

(defn make-lexer
  "Creates the lexer state for `text`. Options:

    :tolerant?       return partial tokens instead of raising on
                     incomplete input
    :greedy-strings? unquoted strings consume everything up to whitespace
                     or a structural delimiter (inline mode)"
  ([text] (make-lexer text nil))
  ([text {:keys [tolerant? greedy-strings?]}]
   {:text (or text "")
    :pos 0
    :line 1
    :column 1
    :last-unitable nil
    :tolerant? (boolean tolerant?)
    :greedy-strings? (boolean greedy-strings?)}))

(defn- char-at
  [{:keys [^String text]} i]
  (when (< -1 i (count text))
    (subs text i (inc i))))

(defn- current
  [st]
  (char-at st (:pos st)))

(defn- peek-char
  ([st] (peek-char st 1))
  ([st n] (char-at st (+ (:pos st) n))))

(defn- advance
  [st]
  (let [pos (:pos st)]
    (if (= (char-at st pos) "\n")
      (assoc st :pos (inc pos) :line (inc (:line st)) :column 1)
      (assoc st :pos (inc pos) :column (inc (:column st))))))

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
  (if (u/space? (current st)) (recur (advance st)) st))

(defn- skip-line
  [st]
  (let [st (loop [st st]
             (let [c (current st)]
               (if (and (some? c) (not= c "\n")) (recur (advance st)) st)))]
    (if (= (current st) "\n") (eat st "\n") st)))

(defn- comment?
  [st]
  (and (= (current st) "/") (= (peek-char st) "/")))

(defn- token
  [type value st start-pos]
  {:type type :value value :line (:line st) :pos start-pos :end-pos (:pos st)})

(defn- digit-start?
  "Python like isdigit that also accepts numbers starting with `.`."
  [st]
  (let [c (current st)]
    (and (some? c)
         (or (u/digit? c)
             (and (= c ".") (u/digit? (peek-char st)))))))

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
  (let [start (:pos st)
        initial (if (= (current st) ".") "0" "")
        ;; Every consumed char is part of the number
        text-of (fn [st] (str initial (subs (:text st) start (:pos st))))]
    (loop [st st has-point? false]
      (let [c (current st)]
        (cond
          (and (some? c) (u/digit? c))
          (recur (advance st) has-point?)

          (= c ".")
          (cond
            ;; A dot not followed by a digit is a method call dot (1.to_string())
            (not (u/digit? (peek-char st)))
            (let [tok (token :number (text-of st) st start)]
              [tok (assoc st :last-unitable tok)])

            has-point?
            (lex-error st :lexer/multiple-decimal-points {:value (str (text-of st) c)})

            :else
            (recur (advance st) true))

          :else
          (let [tok (token :number (text-of st) st start)]
            [tok (assoc st :last-unitable tok)]))))))

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
    (loop [st st simple? true]
      (let [c (current st)]
        (if (check c)
          (recur (advance st)
                 (and simple? (or (u/alphanumeric? c) (= c "-") (= c "_"))))
          [(classify-word st (subs (:text st) start (:pos st)) start simple?) st])))))

(defn- read-reference
  [st]
  (let [st (eat st "{")
        ref-start (:pos st)
        ;; The name is the consumed text without whitespace
        text-of (fn [st spaces?]
                  (let [raw (subs (:text st) ref-start (:pos st))]
                    (if spaces?
                      (->> raw (map str) (remove u/space?) (apply str))
                      raw)))]
    (loop [st st spaces? false]
      (let [c (current st)]
        (cond
          (and (= c "{") (:tolerant? st))
          [(token :partial-reference (text-of st spaces?) st ref-start) st]

          (= c "{")
          (lex-error st :lexer/unterminated-reference {})

          (nil? c)
          (if (:tolerant? st)
            [(token :partial-reference (text-of st spaces?) st ref-start) st]
            (lex-error st :lexer/unterminated-reference {}))

          (= c "}")
          (let [result (text-of st spaces?)]
            (if (= result "")
              (if (:tolerant? st)
                [(token :partial-reference "" st ref-start) st]
                (lex-error st :lexer/empty-variable-name {}))
              (let [ref-end (:pos st)
                    st' (eat st "}")
                    tok {:type :reference :value result :line (:line st')
                         :pos ref-start :end-pos ref-end}]
                ;; Format adjacency uses the position after the closing brace
                [tok (assoc st' :last-unitable (assoc tok :end-pos (:pos st')))])))

          (u/space? c)
          (recur (advance st) true)

          :else
          (recur (advance st) spaces?))))))

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
  (let [start (:pos st)
        [st result] (loop [st st result ""]
                      (let [c (current st)]
                        (if (and (some? c) (or (u/alpha? c) (digit-start? st) (= c "#")))
                          (recur (advance st) (str result c))
                          [st result])))]
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
   (let [start (:pos st)
         st (reduce eat st (map str (seq s)))
         tok (token type value st start)]
     [tok (if unitable? (assoc st :last-unitable tok) st)])))

(defn next-token
  "Returns `[token state']`. Raises on invalid input unless the lexer is
  tolerant, in which case invalid characters are skipped and partial
  tokens are produced."
  [st]
  (let [st (skip-whitespace st)
        c (current st)
        p (peek-char st)]
    (cond
      (nil? c)
      [(token :eof nil st (:pos st)) st]

      (comment? st)
      (recur (skip-line st))

      (digit-start? st) (read-number st)
      (or (= c "'") (= c "\"")) (read-explicit-string st c)
      (= c "`") (read-template-string st)
      (valid-identifier-start? c) (read-word st)
      (= c "{") (read-reference st)
      (= c "[") (simple-token st :lblock "[" "[")
      (= c "]") (simple-token st :rblock "]" "]")
      (and (= c "!") (= p "=")) (simple-token st :is-not-eq "!=" "!=")
      (= c "+") (simple-token st :operation "+" "+")
      (= c "-") (simple-token st :operation "-" "-")
      (= c "*") (simple-token st :operation "*" "*")
      (= c "/") (simple-token st :operation "/" "/")
      (= c "^") (simple-token st :operation "^" "^")
      (= c "!") (simple-token st :operation "!" "!")
      (= c "(") (simple-token st :lparen "(" "(")
      (= c ")") (simple-token st :rparen ")" ")" true)
      (= c ",") (simple-token st :comma "," ",")
      (= c ".") (simple-token st :dot "." ".")
      (= c "#") (read-hex-color st)
      (= c "%") (simple-token st :format "%" "%")
      (and (= c "=") (= p "=")) (simple-token st :is-eq "==" "==")
      (= c "=") (simple-token st :assign "=" "=")
      (and (= c ">") (= p "=")) (simple-token st :is-gt-eq ">=" ">=")
      (= c ">") (simple-token st :is-gt ">" ">")
      (and (= c "<") (= p "=")) (simple-token st :is-lt-eq "<=" "<=")
      (= c "<") (simple-token st :is-lt "<" "<")
      (= c ";") (simple-token st :semicolon ";" ";")
      (and (= c "&") (= p "&")) (simple-token st :logic-and "&&" "&&")
      (and (= c "|") (= p "|")) (simple-token st :logic-or "||" "||")
      (= c ":") (simple-token st :colon ":" ":")

      (:tolerant? st)
      (recur (advance st))

      :else
      (lex-error st :lexer/invalid-character {:char c :position (:pos st)}))))

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
