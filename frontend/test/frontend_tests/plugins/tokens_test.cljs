;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.tokens-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.tokenscript :as ts]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.tokens :as ptok]
   [app.plugins.utils :as u]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(def ^:private get-resolved-value @#'ptok/get-resolved-value)

;; Regression coverage for issue #9162.
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
;;
;; These helper-level tests pin the string-friendly conversion contract;
;; the schema-level fix is covered by the existing plugin integration
;; suite that exercises `applyToken` end-to-end.

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

(t/deftest token-attr-plugin->token-attr-resolves-padding-margin-side-aliases
  (t/is (= :p1 (ptok/token-attr-plugin->token-attr :padding-top)))
  (t/is (= :p2 (ptok/token-attr-plugin->token-attr :padding-right)))
  (t/is (= :p3 (ptok/token-attr-plugin->token-attr :padding-bottom)))
  (t/is (= :p4 (ptok/token-attr-plugin->token-attr :padding-left)))
  (t/is (= :m1 (ptok/token-attr-plugin->token-attr :margin-top)))
  (t/is (= :m2 (ptok/token-attr-plugin->token-attr :margin-right)))
  (t/is (= :m3 (ptok/token-attr-plugin->token-attr :margin-bottom)))
  (t/is (= :m4 (ptok/token-attr-plugin->token-attr :margin-left))))

(t/deftest token-attr-plugin->token-attr-coerces-string-input
  ;; This is the actual regression — JS plugin calls supply strings.
  (t/is (= :fill (ptok/token-attr-plugin->token-attr "fill")))
  (t/is (= :stroke-color (ptok/token-attr-plugin->token-attr "stroke-color")))
  ;; Verbose plugin aliases work via the string path too.
  (t/is (= :r1 (ptok/token-attr-plugin->token-attr "border-radius-top-left")))
  (t/is (= :m3 (ptok/token-attr-plugin->token-attr "margin-bottom-right"))))

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

(t/deftest shape-apply-token-accepts-padding-top
  (t/async
    done
    (let [set-id    (cthi/new-id! :token-set)
          token-id  (cthi/new-id! :spacing-token)
          file      (-> (cthf/sample-file :file1 :page-label :page1)
                        (ctho/add-frame :frame1 {:layout :flex})
                        (ctht/add-tokens-lib)
                        (ctht/update-tokens-lib
                         #(-> %
                              (ctob/add-set
                               (ctob/make-token-set :id set-id
                                                    :name "spacing"))
                              (ctob/add-theme
                               (ctob/make-token-theme :name "theme"
                                                      :sets #{"spacing"}))
                              (ctob/set-active-themes #{"/theme"})
                              (ctob/add-token
                               set-id
                               (ctob/make-token :id token-id
                                                :name "spacing.medium"
                                                :type :spacing
                                                :value 16)))))
          store     (ths/setup-store file)
          _         (set! st/state store)
          _         (set! st/stream (ptk/input-stream store))
          ^js context   (api/create-context "00000000-0000-0000-0000-000000000000")
          ^js page      (.-currentPage context)
          ^js shape     (.getShapeById page (str (cthi/id :frame1)))
          ^js library   (.-library context)
          ^js local     (.-local library)
          ^js catalog   (.-tokens local)
          ^js token-set (.getSetById catalog (str set-id))
          ^js token     (.getTokenById token-set (str token-id))]
      (.applyToken shape token #js ["paddingTop"])
      (js/setTimeout
       (fn []
         (let [shape-id (cthi/id :frame1)
               page-id  (cthf/current-page-id file)]
           (t/is (= "spacing.medium" (.. shape -tokens -paddingTopLeft)))
           (t/is (= "spacing.medium"
                    (get-in @store
                            [:files (:id file) :data :pages-index page-id
                             :objects shape-id :applied-tokens :p1])))
           (done)))
       0))))

(t/deftest token-attr?-rejects-unknown-input
  (t/is (false? (boolean (ptok/token-attr? :not-a-real-attr))))
  (t/is (false? (boolean (ptok/token-attr? "not-a-real-attr"))))
  (t/is (false? (boolean (ptok/token-attr? nil)))))

;; Regression coverage for issue #10070.
;;
;; The Plugin API's `addToken` rejected reference tokens whose target
;; lives in an *inactive* token set, even though the referenced token
;; exists structurally. The proxy `:fn` resolved the new token against
;; active sets only (`get-tokens-in-active-sets`), so a reference into an
;; inactive set never resolved and fell into the generic `not-valid`
;; error path.
;;
;; The fix resolves against *all* tokens in the library (inactive sets
;; included), mirroring the workspace token-creation form. These tests
;; reproduce the exact `tokens-tree` construction from both the buggy and
;; the fixed `addToken` `:fn` and assert resolution behaviour directly —
;; the proxy `:fn` itself drives the global store and `st/emit!`, so it is
;; not unit-testable, but the resolve step it gates on is.

(defn- inactive-set-library
  "A library with `primitives` (holding `color.gray.50`) left inactive and
  an active, empty `semantic` set — the repro from the issue."
  []
  (-> (ctob/make-tokens-lib)
      (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :primitives)
                                         :name "primitives"))
      (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :semantic)
                                         :name "semantic"))
      (ctob/add-token (cthi/id :primitives)
                      (ctob/make-token {:name "color.gray.50"
                                        :value "#fafafa"
                                        :type :color}))
      ;; `add-set` does not activate sets, so activate only `semantic`,
      ;; leaving `primitives` (the reference target) inactive.
      (ctob/toggle-set-in-theme ctob/hidden-theme-id "semantic")))

(t/deftest add-token-active-sets-only-fails-to-resolve-cross-set-reference
  ;; Demonstrates the bug: resolving the new token against active sets
  ;; only leaves the reference unresolved.
  (let [tokens-lib (inactive-set-library)
        token (ctob/make-token {:name "color.bg.default"
                                :value "{color.gray.50}"
                                :type :color})
        tokens-tree (-> (ctob/get-tokens-in-active-sets tokens-lib)
                        (assoc (:name token) token))
        resolved (ts/resolve-tokens tokens-tree)
        {:keys [errors resolved-value]} (get resolved (:name token))]
    (t/is (nil? resolved-value))
    (t/is (seq errors))))

(t/deftest add-token-resolves-cross-set-reference-into-inactive-set
  ;; The fix: resolving against all tokens in the library (inactive sets
  ;; included) resolves the reference even though `primitives` is inactive.
  (let [tokens-lib (inactive-set-library)
        token (ctob/make-token {:name "color.bg.default"
                                :value "{color.gray.50}"
                                :type :color})
        tokens-tree (-> (merge (ctob/get-all-tokens-map tokens-lib)
                               (ctob/get-tokens tokens-lib (cthi/id :semantic)))
                        (assoc (:name token) token))
        resolved (ts/resolve-tokens tokens-tree)
        {:keys [errors resolved-value]} (get resolved (:name token))]
    (t/is (some? resolved-value))
    (t/is (empty? errors))))

(t/deftest add-token-still-fails-for-references-missing-from-every-set
  ;; A reference to a token that exists in *no* set must still fail, even
  ;; with the all-tokens resolution.
  (let [tokens-lib (inactive-set-library)
        token (ctob/make-token {:name "color.bg.default"
                                :value "{color.does.not.exist}"
                                :type :color})
        tokens-tree (-> (merge (ctob/get-all-tokens-map tokens-lib)
                               (ctob/get-tokens tokens-lib (cthi/id :semantic)))
                        (assoc (:name token) token))
        resolved (ts/resolve-tokens tokens-tree)
        {:keys [errors resolved-value]} (get resolved (:name token))]
    (t/is (nil? resolved-value))
    (t/is (seq errors))))

(t/deftest token-set-duplicate-returns-the-duplicated-set
  (let [file-id (cthi/new-id! :file)
        set-id  (cthi/new-id! :set)
        dup-id  (cthi/new-id! :dup)
        proxy   (ptok/token-set-proxy "plugin-id" file-id set-id)]
    (with-redefs [dwtl/duplicate-token-set
                  (mock/stub (fn [id {:keys [id-ref]}]
                               (t/is (= set-id id))
                               (reset! id-ref dup-id)
                               :duplicate-token-set))
                  st/emit! mock/noop]
      (let [dup (.duplicate proxy)]
        (t/is (ptok/token-set-proxy? dup))
        (t/is (= (str dup-id) (.-id dup)))))))

(t/deftest theme-add-set-and-remove-set-use-the-set-name
  (let [file-id  (cthi/new-id! :file)
        theme-id (cthi/new-id! :theme)
        set-id   (cthi/new-id! :set)
        set      (ptok/token-set-proxy "plugin-id" file-id set-id "Primitives")
        theme    (ptok/token-theme-proxy "plugin-id" file-id theme-id)
        captured (atom [])]
    (with-redefs [u/locate-token-theme
                  (fn [_file _theme]
                    (ctob/make-token-theme :id theme-id
                                           :name "Theme"
                                           :sets #{"Primitives"}))
                  dwtl/update-token-theme
                  (fn [id theme]
                    (swap! captured conj {:id id :theme theme})
                    :update-token-theme)
                  st/emit! identity]
      (.addSet theme set)
      (.removeSet theme set)
      (t/is (= [theme-id theme-id] (mapv :id @captured)))
      (t/is (contains? (-> @captured first :theme :sets) "Primitives"))
      (t/is (not (contains? (-> @captured second :theme :sets) "Primitives"))))))

(t/deftest font-family-token-value-accepts-a-string
  (let [file-id  (cthi/new-id! :file)
        set-id   (cthi/new-id! :set)
        token-id (cthi/new-id! :token)
        captured (atom nil)]
    (with-redefs [u/locate-token (constantly {:id token-id
                                              :name "font.primary"
                                              :type :font-family
                                              :value ["Inter"]})
                  dwtl/update-token (mock/stub (fn [set-id token-id attrs]
                                                 (reset! captured {:set-id set-id
                                                                   :token-id token-id
                                                                   :attrs attrs})
                                                 :update-token))
                  st/emit! mock/noop]
      (let [token (ptok/token-proxy "plugin-id" file-id set-id token-id)]
        (set! (.-value token) "Inter, Arial")
        (t/is (= set-id (:set-id @captured)))
        (t/is (= token-id (:token-id @captured)))
        (t/is (= ["Inter" "Arial"] (get-in @captured [:attrs :value])))))))

(t/deftest typography-token-resolved-value-is-plugin-array-shape
  (let [token (ctob/make-token
               {:name "type.body"
                :type :typography
                :value {:font-family ["Inter" "Arial"]
                        :font-size "16px"
                        :font-weight "600"
                        :line-height "20px"
                        :letter-spacing "1"
                        :text-case "uppercase"
                        :text-decoration "underline"}})
        result (get-resolved-value token {(:name token) token})
        entry  (aget result 0)]
    (t/is (array? result))
    (t/is (= ["Inter" "Arial"] (vec (aget entry "fontFamilies"))))
    (t/is (= 16 (aget entry "fontSizes")))
    (t/is (= "600" (aget entry "fontWeights")))
    (t/is (= 20 (aget entry "lineHeight")))
    (t/is (= "uppercase" (aget entry "textCase")))
    (t/is (= "underline" (aget entry "textDecoration")))))

(t/deftest shadow-token-resolved-value-is-plugin-array-shape
  (let [token (ctob/make-token
               {:name "shadow.card"
                :type :shadow
                :value [{:offset-x "1px"
                         :offset-y "2px"
                         :blur "3px"
                         :spread "4px"
                         :color "#000000"
                         :inset false}]})
        result (get-resolved-value token {(:name token) token})
        entry  (aget result 0)]
    (t/is (array? result))
    (t/is (= 1 (aget entry "offsetX")))
    (t/is (= 2 (aget entry "offsetY")))
    (t/is (= 3 (aget entry "blur")))
    (t/is (= 4 (aget entry "spread")))))

(t/deftest font-family-token-resolved-value-is-string-array
  (let [token (ctob/make-token
               {:name "font.primary"
                :type :font-family
                :value ["Inter" "Arial"]})
        result (get-resolved-value token {(:name token) token})]
    (t/is (array? result))
    (t/is (= ["Inter" "Arial"] (vec result)))))
