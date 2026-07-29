;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.utils-test
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.schema :as sm]
   [app.plugins.utils :as plugins.utils]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]))

;; The plugin error renderer goes through `tr`, which returns the bare
;; translation code when no locale data is loaded. Load the strings this
;; namespace asserts on so the assertions exercise the real rendering.
(i18n/set-default-translations
 #js {"plugins.validation.message" "Field %s is invalid: %s"
      "plugins.validation.invalid-value" "expected %s, got %s (%s)"
      "plugins.validation.received-value" "got %s (%s)"
      "errors.invalid-data" "Invalid data"
      "errors.field-missing" "Missing field"
      "errors.tokens.empty-input" "Empty input"})

;; Access the private flattener for direct testing.
(def ^:private flatten-error-map @#'plugins.utils/flatten-error-map)

(t/deftest test-flatten-error-map-flat-input
  ;; Regression test for issue #9417.
  ;;
  ;; When a malli error path has a single element, `interpret-schema-problem`
  ;; produces a flat map. The flattener must pass that through unchanged.
  (let [result (flatten-error-map {:group {:message "must be string"}})]
    (t/is (= [["group" "must be string"]] result))))

(t/deftest test-flatten-error-map-nested-input
  ;; Regression test for issue #9417.
  ;;
  ;; When a malli error path has multiple elements, `interpret-schema-problem`
  ;; produces a nested map via `(assoc-in acc path …)`. The old plugin
  ;; consumer destructured assuming a flat shape, so the nested case rendered
  ;; the message text as the field name (`Field message is invalid`) instead
  ;; of the real validation reason. The flattener must descend until it finds
  ;; a leaf carrying a `:message`.
  (let [explain {:sets {0 {:name {:message "must not be empty"}}}}
        result  (set (flatten-error-map explain))]
    (t/is (= #{["sets.0.name" "must not be empty"]} result))))

(t/deftest test-flatten-error-map-multiple-fields
  ;; Multiple validation problems on the same explain produce multiple
  ;; entries, none of which clobber each other.
  (let [explain {:group {:message "must be string"}
                 :sets  {0 {:message "set not found"}}}
        result  (set (flatten-error-map explain))]
    (t/is (= #{["group" "must be string"]
               ["sets.0"  "set not found"]} result))))

(t/deftest test-flatten-error-map-mixed-key-types
  ;; Numeric indices (from vector positions in the malli path) must render
  ;; cleanly; string keys must also be accepted alongside keywords.
  (let [explain {:items {2 {"label" {:message "invalid label"}}}}
        [[path message]] (flatten-error-map explain)]
    (t/is (= "items.2.label" path))
    (t/is (= "invalid label" message))))

(t/deftest test-flatten-error-map-empty
  ;; No validation errors -> no output (callers join with ". " and would
  ;; otherwise emit an empty string, which is fine).
  (t/is (empty? (flatten-error-map {}))))

;; ---------------------------------------------------------------------
;; Issue #9692 — `handle-error` must surface a useful message instead of a
;; bare "Value not valid. Code: :error".
;;
;; `not-valid` is redefined to capture the rendered message directly, so the
;; assertions don't depend on `st/state` or the console.

(t/deftest test-handle-error-plain-js-error
  ;; A plain JS error has no `::sm/explain` and CLJS `ex-data` returns nil, so
  ;; the handler must fall back to the error's own message rather than nil.
  (let [captured (atom ::none)]
    (with-redefs [plugins.utils/not-valid (fn [_plugin-id _code value]
                                            (reset! captured value) nil)]
      ((plugins.utils/handle-error #uuid "00000000-0000-0000-0000-000000000000")
       (js/Error. "boom: not a function")))
    (t/is (= "boom: not a function" @captured))))

(t/deftest test-handle-error-empty-explain
  ;; An explain whose errors don't render any message must not produce an
  ;; empty string; the handler falls back to the raw explain.
  (let [captured (atom ::none)
        cause    (ex-info "invalid" {:app.common.schema/explain {:errors [] :value 1}})]
    (with-redefs [plugins.utils/not-valid (fn [_plugin-id _code value]
                                            (reset! captured value) nil)]
      ((plugins.utils/handle-error #uuid "00000000-0000-0000-0000-000000000000") cause))
    (t/is (string? @captured))
    (t/is (not= "" @captured))))

(t/deftest test-error-messages-empty-returns-nil
  ;; `error-messages` returns nil (not "") on an explain with no mappable
  ;; errors, so `handle-error` can distinguish "no message" from a real one.
  (t/is (nil? (plugins.utils/error-messages {:errors []}))))

;; ---------------------------------------------------------------------
;; Issue #10072 — schema validation errors must say what was expected and
;; what was received, instead of collapsing into a generic "Invalid data".

(t/deftest test-error-messages-reports-expected-and-received
  (let [explain (sm/explain [:map [:value :string]] {:value 16})
        message (plugins.utils/error-messages explain)]
    (t/is (= "Field value is invalid: expected string, got number (16)" message))))

(t/deftest test-error-messages-reports-nested-field-path
  (let [explain (sm/explain [:map [:sets [:vector [:map [:name :string]]]]]
                            {:sets [{:name true}]})
        message (plugins.utils/error-messages explain)]
    (t/is (= "Field sets.0.name is invalid: expected string, got boolean (true)" message))))

(t/deftest test-error-messages-honors-custom-schema-message
  ;; A schema that declares its own `:error/message` must keep it; the
  ;; expected/received rendering is only a fallback.
  (let [explain (sm/explain [:map [:value [:string {:error/message "must not be empty"}]]]
                            {:value 16})
        message (plugins.utils/error-messages explain)]
    (t/is (= "Field value is invalid: must not be empty" message))))

(t/deftest test-error-messages-missing-key
  ;; Missing keys keep the "field missing" message: there is no received
  ;; value to report.
  (let [explain (sm/explain [:map [:value :string]] {})
        message (plugins.utils/error-messages explain)]
    (t/is (= "Field value is invalid: Missing field" message))))

(t/deftest test-error-messages-never-generic
  (let [explain (sm/explain [:map [:value :string]] {:value 16})
        message (plugins.utils/error-messages explain)]
    (t/is (not (str/includes? message "Invalid data")))))

(t/deftest test-error-messages-truncates-long-values
  (let [value   (apply str (repeat 500 "a"))
        explain (sm/explain [:map [:value :int]] {:value value})
        message (plugins.utils/error-messages explain)]
    (t/is (str/includes? message "got string"))
    (t/is (< (count message) 200))))

(t/deftest test-error-messages-unicode-value
  (let [explain (sm/explain [:map [:value :int]] {:value "ñ😀"})
        message (plugins.utils/error-messages explain)]
    (t/is (str/includes? message "ñ😀"))))

(t/deftest test-error-messages-collection-value
  (let [explain (sm/explain [:map [:value :string]] {:value [1 2]})
        message (plugins.utils/error-messages explain)]
    (t/is (str/includes? message "got array"))))

(defn- proxy? [value] (and (map? value) (contains? value :proxy)))

(defn- well-formed?
  "False when the string contains a lone UTF-16 surrogate."
  [s]
  (try
    (js/encodeURIComponent s)
    true
    (catch :default _ false)))

(t/deftest test-error-messages-union-reports-every-branch
  ;; Malli emits one problem per `:or` branch, all sharing the same path.
  ;; Reporting a single branch as if it were the only requirement is worse
  ;; than saying nothing: `plugins.tokens` declares the token value setter
  ;; as `[:or :string base]`, so a plugin author would be told a vector is
  ;; required when a plain string is equally valid.
  (let [explain (sm/explain [:map [:value [:or [:vector :string] :string]]] {:value 16})
        message (plugins.utils/error-messages explain)]
    (t/is (= "Field value is invalid: expected [:vector :string] or string, got number (16)"
             message))))

(t/deftest test-error-messages-union-reports-every-branch-nested
  (let [explain (sm/explain [:map [:sets [:vector [:or :string :int]]]] {:sets [true]})
        message (plugins.utils/error-messages explain)]
    (t/is (= "Field sets.0 is invalid: expected string or int, got boolean (true)"
             message))))

(t/deftest test-error-messages-never-leaks-function-objects
  ;; `[:fn pred]` schemas (used by the token-set arguments) render their form
  ;; as `#object[app$plugins$tokens$token_set_proxy_QMARK_]`. That must never
  ;; reach a plugin author.
  (let [explain (sm/explain [:map [:value [:or [:fn proxy?] :string]]] {:value 16})
        message (plugins.utils/error-messages explain)]
    (t/is (not (str/includes? message "#object")))
    (t/is (= "Field value is invalid: got number (16)" message))))

(t/deftest test-error-messages-function-schema-falls-back-to-custom-message
  ;; When one branch of the union carries its own message, that message wins
  ;; over the un-renderable `[:fn …]` branch.
  (let [explain (sm/explain [:map [:value [:or [:fn proxy?] ::sm/uuid]]] {:value "nope"})
        message (plugins.utils/error-messages explain)]
    (t/is (not (str/includes? message "#object")))
    (t/is (= "Field value is invalid: should be an uuid" message))))

(t/deftest test-error-messages-truncation-keeps-surrogate-pairs
  ;; Truncating at a UTF-16 code-unit boundary splits astral characters and
  ;; produces mojibake in a message whose whole purpose is legibility.
  (let [value   (apply str (repeat 200 "😀"))
        explain (sm/explain [:map [:value :int]] {:value value})
        message (plugins.utils/error-messages explain)]
    (t/is (well-formed? message))
    (t/is (str/includes? message "…"))))

(t/deftest test-error-messages-truncation-counts-code-points
  ;; 60 emoji are 120 UTF-16 code units but only 60 code points: below the
  ;; limit, so the value must be reported whole.
  (let [value   (apply str (repeat 60 "😀"))
        explain (sm/explain [:map [:value :int]] {:value value})
        message (plugins.utils/error-messages explain)]
    (t/is (well-formed? message))
    (t/is (str/includes? message value))))

;; ---------------------------------------------------------------------
;; The value reported back to the plugin author is arbitrary JS data, so
;; rendering it must terminate and must not run plugin code.

(t/deftest test-error-messages-self-referencing-object
  ;; A plugin argument is a native JS value, and an object graph with a back
  ;; reference (`a.parent.child === a`) is ordinary. `pr-str` walks it forever,
  ;; so the error renderer would throw a RangeError from inside the error
  ;; handler itself: the author gets a stack overflow instead of a validation
  ;; error.
  (let [a       #js {}
        b       #js {}
        _       (unchecked-set a "parent" b)
        _       (unchecked-set b "child" a)
        explain (sm/explain [:map [:value :string]] {:value a})
        message (plugins.utils/error-messages explain)]
    (t/is (str/includes? message "got object"))))

(t/deftest test-error-messages-object-with-getters
  ;; Penpot's own proxies expose their contents through getters that read the
  ;; application state and may throw for a stale id. Rendering a value must
  ;; never invoke them.
  (let [called  (atom 0)
        value   (js/Object.defineProperty
                 #js {:id "x"} "children"
                 #js {:enumerable true
                      :get (fn [] (swap! called inc) (throw (js/Error. "boom")))})
        explain (sm/explain [:map [:value :string]] {:value value})
        message (plugins.utils/error-messages explain)]
    (t/is (str/includes? message "got object"))
    (t/is (zero? @called))))

(t/deftest test-error-messages-deeply-nested-object
  ;; A deep graph must not produce an unbounded message.
  (let [value   (reduce (fn [acc _] #js {:child acc}) #js {:leaf 1} (range 200))
        explain (sm/explain [:map [:value :string]] {:value value})
        message (plugins.utils/error-messages explain)]
    (t/is (str/includes? message "got object"))
    (t/is (< (count message) 200))))

;; ---------------------------------------------------------------------
;; Issue #10072 — the reported example: `addToken` with a wrong token value.
;;
;; The token value schemas declare an `:error/fn` that only speaks about empty
;; values, so it returns nil for a wrong-typed one and `interpret-schema-problem`
;; degrades to the generic "Invalid data". A message that says nothing must not
;; win over one that says what was expected and what was received.

(t/deftest test-error-messages-token-value-numeric
  (let [schema  [:map [:value (cfo/make-token-value-schema :font-size)]]
        message (plugins.utils/error-messages (sm/explain schema {:value 16}))]
    (t/is (= "Field value is invalid: expected string, got number (16)" message))))

(t/deftest test-error-messages-token-value-opacity
  (let [schema  [:map [:value (cfo/make-token-value-schema :opacity)]]
        message (plugins.utils/error-messages (sm/explain schema {:value true}))]
    (t/is (= "Field value is invalid: expected string, got boolean (true)" message))))

(t/deftest test-error-messages-token-value-font-family
  ;; Both branches of the union are printable, so both are reported.
  (let [schema  [:map [:value (cfo/make-token-value-schema :font-family)]]
        message (plugins.utils/error-messages (sm/explain schema {:value 16}))]
    (t/is (= (str "Field value is invalid: "
                  "expected [:vector :app.common.schema/text] or TokenRef, got number (16)")
             message))))

(t/deftest test-error-messages-token-value-empty-keeps-custom-message
  ;; When the schema's own `:error/fn` does render a message, it still wins.
  (let [schema  [:map [:value (cfo/make-token-value-schema :font-size)]]
        message (plugins.utils/error-messages (sm/explain schema {:value ""}))]
    (t/is (= "Field value is invalid: Empty input" message))))

