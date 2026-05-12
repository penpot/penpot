;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.tokens-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.tokens-lib :as ctob]
   [app.main.store :as st]
   [app.plugins.shape :as pshape]
   [app.plugins.tokens :as ptok]
   [app.plugins.utils :as putils]
   [app.util.object :as obj]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [potok.v2.core :as ptk]))

;; ---------------------------------------------------------------------
;; Issue #9162 — `token-attr-plugin->token-attr` regression coverage.
;;
;; Plugin code calling `shape.applyToken(token, ["fill"])` or
;; `token.applyToShapes([rect], ["fill"])` from JavaScript supplies a JS
;; array of strings. Penpot's plugin proxies expect a Clojure set of
;; keywords. Two coupled defects made these calls silently no-op (or, with
;; `throwValidationErrors` enabled, throw a "check error"):
;;
;; 1. `token-attr-plugin->token-attr` only consulted its alias map when
;;    the input was already a keyword — string inputs like "fill" or
;;    "border-radius-top-left" fell through to the identity branch
;;    unchanged, so the downstream `cto/token-attr?` predicate (which
;;    checks against a set of keywords) returned false.
;; 2. The `applyToken` / `applyToShapes` / `applyToSelected` schemas used
;;    plain `[:set ...]`, which does not have a `:decode/json`
;;    transformer for the JS array → Clojure set coercion. Penpot's
;;    custom `[::sm/set ...]` does. Switching to the registered set type
;;    lets the standard JSON decoder pipeline turn the JS argument into
;;    a set of strings, after which the `[:and ::sm/keyword [:fn
;;    token-attr?]]` element schema coerces each string to a keyword and
;;    validates it.

(t/deftest token-attr-plugin->token-attr-passes-canonical-form-through
  ;; Both already-canonical short names and unaliased names pass through
  ;; unchanged.
  (t/is (= :fill (ptok/token-attr-plugin->token-attr :fill)))
  (t/is (= :stroke-color (ptok/token-attr-plugin->token-attr :stroke-color)))
  (t/is (= :r1 (ptok/token-attr-plugin->token-attr :r1)))
  (t/is (= :p2 (ptok/token-attr-plugin->token-attr :p2))))

(t/deftest token-attr-plugin->token-attr-resolves-verbose-plugin-aliases
  ;; Plugin-side verbose names (e.g. `:border-radius-top-left`) map to
  ;; their canonical short internal form (`:r1`) so plugin authors can
  ;; spell the corner explicitly without the engine having to know both.
  (t/is (= :r1 (ptok/token-attr-plugin->token-attr :border-radius-top-left)))
  (t/is (= :r2 (ptok/token-attr-plugin->token-attr :border-radius-top-right)))
  (t/is (= :r3 (ptok/token-attr-plugin->token-attr :border-radius-bottom-right)))
  (t/is (= :r4 (ptok/token-attr-plugin->token-attr :border-radius-bottom-left)))
  (t/is (= :p1 (ptok/token-attr-plugin->token-attr :padding-top-left)))
  (t/is (= :m3 (ptok/token-attr-plugin->token-attr :margin-bottom-right))))

(t/deftest token-attr-plugin->token-attr-coerces-string-input
  ;; This is the actual regression — JS plugin calls supply strings.
  (t/is (= :fill (ptok/token-attr-plugin->token-attr "fill")))
  (t/is (= :stroke-color (ptok/token-attr-plugin->token-attr "stroke-color")))
  ;; Verbose plugin aliases work via the string path too.
  (t/is (= :r1 (ptok/token-attr-plugin->token-attr "border-radius-top-left")))
  (t/is (= :m3 (ptok/token-attr-plugin->token-attr "margin-bottom-right"))))

(t/deftest token-attr-plugin->token-attr-accepts-camelcase
  ;; The read side of `shape.tokens` exposes property names as
  ;; camelCase (via `json/write-camel-key`). For `shape.tokens.X = ...`
  ;; to round-trip, the setter side must accept the same shape. (#9561)
  (t/is (= :stroke-color (ptok/token-attr-plugin->token-attr "strokeColor")))
  (t/is (= :r1 (ptok/token-attr-plugin->token-attr "borderRadiusTopLeft")))
  (t/is (= :m3 (ptok/token-attr-plugin->token-attr "marginBottomRight"))))

(t/deftest token-attr?-accepts-keyword-input
  (t/is (true? (boolean (ptok/token-attr? :fill))))
  (t/is (true? (boolean (ptok/token-attr? :stroke-color))))
  (t/is (true? (boolean (ptok/token-attr? :r1))))
  (t/is (true? (boolean (ptok/token-attr? :p2)))))

(t/deftest token-attr?-accepts-string-input
  ;; Same JS-array-of-strings reproducer as the issue, exercised at the
  ;; predicate layer the plugin schemas call into.
  (t/is (true? (boolean (ptok/token-attr? "fill"))))
  (t/is (true? (boolean (ptok/token-attr? "stroke-color"))))
  (t/is (true? (boolean (ptok/token-attr? "r1"))))
  (t/is (true? (boolean (ptok/token-attr? "m3")))))

(t/deftest token-attr?-rejects-unknown-input
  (t/is (false? (boolean (ptok/token-attr? :not-a-real-attr))))
  (t/is (false? (boolean (ptok/token-attr? "not-a-real-attr"))))
  (t/is (false? (boolean (ptok/token-attr? nil)))))

;; ---------------------------------------------------------------------
;; Issue #9561 — error formatter robustness.
;;
;; When a plugin schema validation fails at a positional path (tuple or
;; vector), `interpret-schema-problem` builds a map keyed by integer
;; indices. The legacy error formatter called `(name field)` on those
;; integers and crashed with "Doesn't support name: 0". That secondary
;; throw masked the real validation failure and was reported to plugin
;; authors as the visible error.

(t/deftest format-field-handles-keyword-string-number-and-path
  ;; Direct coverage of the new `format-field` helper. Keywords/strings
  ;; are unchanged; numbers stringify; vector paths are dotted.
  (t/is (= "fill" (#'putils/format-field :fill)))
  (t/is (= "color" (#'putils/format-field "color")))
  (t/is (= "0" (#'putils/format-field 0)))
  (t/is (= "1.2" (#'putils/format-field [1 2])))
  (t/is (= "shapes.0" (#'putils/format-field [:shapes 0]))))

(t/deftest error-messages-does-not-crash-on-integer-field
  ;; Validation against `[:tuple ...]` produces errors with `:in [0]`.
  ;; The old formatter called `(name 0)` on the field path and threw
  ;; "Doesn't support name: 0" — the exact symptom users hit in #9561.
  ;; We don't care about the exact message; we just want a string back.
  (let [explain {:errors [{:schema [:tuple :keyword]
                           :in [0]
                           :value "bad"}]}]
    (t/is (string? (putils/error-messages explain)))))

;; ---------------------------------------------------------------------
;; Issue #9561 — end-to-end coverage for `token.applyToShapes` and the
;; `shape.tokens` setter.
;;
;; Async tests using `tohs/run-store-async` rely on the wasm mocks
;; persisting until `done` fires; the synchronous `with-wasm-mocks*`
;; helper would tear them down too early, so we install them via a
;; fixture instead (see helpers/wasm.cljs).

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(def color-token
  {:name "colors.primary"
   :value "#3525CD"
   :type :color})

(defn setup-tokens-file
  []
  (-> (cthf/sample-file :file1 :page-label :page1)
      (ctho/add-rect :rect-1)
      (assoc-in [:data :tokens-lib]
                (-> (ctob/make-tokens-lib)
                    (ctob/add-theme (ctob/make-token-theme :name "Theme A" :sets #{"Set A"}))
                    (ctob/set-active-themes #{"/Theme A"})
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-a)
                                                       :name "Set A"))
                    (ctob/add-token (cthi/id :set-a)
                                    (ctob/make-token color-token))))))

(defn rect-from-state
  [state file rect-id]
  (let [page-id (cthf/current-page-id file)]
    (get-in state
            [:files (:id file)
             :data :pages-index page-id
             :objects rect-id])))

(defn- trigger
  "Wrap a side-effecting plugin call as a ptk EffectEvent so it can be
  fed through `run-store-async` and have its emitted events tracked."
  [f]
  (ptk/reify ::trigger
    ptk/EffectEvent
    (effect [_ _ _] (f))))

(t/deftest test-apply-to-shapes-from-js-array
  ;; Regression for #9561: calling
  ;;   token.applyToShapes([shape], ["fill"])
  ;; from JS code used to throw "Doesn't support name: 0". After the fix
  ;; it must apply the token and update the shape's `:applied-tokens`.
  (t/async
   done
   (let [file       (setup-tokens-file)
         store      (ths/setup-store file)
         _          (set! st/state store)
         plugin-id  "00000000-0000-0000-0000-000000000000"

         rect-1     (cths/get-shape file :rect-1)
         page-id    (cthf/current-page-id file)
         set-id     (cthi/id :set-a)
         token      (-> (get-in file [:data :tokens-lib])
                        (ctob/get-tokens-in-active-sets)
                        (get "colors.primary"))

         ^js shape  (pshape/shape-proxy plugin-id (:id file) page-id (:id rect-1))
         ^js tok    (ptok/token-proxy plugin-id (:id file) set-id (:id token))]

     (t/is (some? shape) "shape proxy should be obtained")
     (t/is (some? tok)   "token proxy should be obtained")

     (tohs/run-store-async
      store done
      ;; Pre-fix: this call would throw "Doesn't support name: 0".
      [(trigger (fn [] (.applyToShapes tok #js [shape] #js ["fill"])))]
      (fn [state]
        (let [rect-1' (rect-from-state state file (:id rect-1))]
          (t/is (= "colors.primary"
                   (get-in rect-1' [:applied-tokens :fill]))
                "token name should be recorded on the shape's :applied-tokens")))))))

(t/deftest test-shape-tokens-setter-applies-token
  ;; Regression for #9561 part 2: assigning to `shape.tokens.fill = "name"`
  ;; used to silently fail because `:tokens` had no setter. After the fix
  ;; the per-property assignment looks up the token in the active library
  ;; and applies it via the existing `toggle-token` action.
  (t/async
   done
   (let [file        (setup-tokens-file)
         store       (ths/setup-store file)
         _           (set! st/state store)
         plugin-id   "00000000-0000-0000-0000-000000000000"

         rect-1      (cths/get-shape file :rect-1)
         page-id     (cthf/current-page-id file)
         ^js shape   (pshape/shape-proxy plugin-id (:id file) page-id (:id rect-1))
         ^js tokens  (. shape -tokens)]

     (t/is (some? tokens) "shape.tokens should always return an object")

     (tohs/run-store-async
      store done
      ;; Pre-fix: this assignment was silently discarded.
      [(trigger (fn [] (obj/set! tokens "fill" "colors.primary")))]
      (fn [state]
        (let [rect-1' (rect-from-state state file (:id rect-1))]
          (t/is (= "colors.primary"
                   (get-in rect-1' [:applied-tokens :fill]))
                "the token should be recorded on the shape's :applied-tokens")))))))

(t/deftest test-shape-tokens-setter-rejects-unknown-token
  ;; The Proxy reports invalid inputs through `u/not-valid` rather than
  ;; silently swallowing them. We verify the underlying state is left
  ;; untouched (no applied token).
  (t/async
   done
   (let [file        (setup-tokens-file)
         store       (ths/setup-store file)
         _           (set! st/state store)
         plugin-id   "00000000-0000-0000-0000-000000000000"

         rect-1      (cths/get-shape file :rect-1)
         page-id     (cthf/current-page-id file)
         ^js shape   (pshape/shape-proxy plugin-id (:id file) page-id (:id rect-1))
         ^js tokens  (. shape -tokens)]

     (tohs/run-store-async
      store done
      [(trigger (fn [] (obj/set! tokens "fill" "colors.this-token-does-not-exist")))]
      (fn [state]
        (let [rect-1' (rect-from-state state file (:id rect-1))]
          (t/is (nil? (get-in rect-1' [:applied-tokens :fill]))
                "no token should be applied when the name doesn't resolve")))))))
