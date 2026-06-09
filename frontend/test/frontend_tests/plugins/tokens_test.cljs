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
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.tokens :as ptok]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

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
