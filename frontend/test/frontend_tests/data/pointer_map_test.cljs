;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.pointer-map-test
  "Unit tests for the workspace fragment resolver: resolved files come
  back whole, and a failing fragment rejects instead of silently
  dropping the page."
  (:require
   [app.common.transit :as tr]
   [app.common.uuid :as uuid]
   [app.main.features.pointer-map :as fpmap]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as a]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private file-id (uuid/custom 10 10))
(def ^:private frag-id (uuid/custom 20 20))
(def ^:private page-id (uuid/custom 30 30))

(def ^:private fragment-content {:objects {}})

(defn- file-with-pointers
  []
  {:id file-id
   :data {:options {}
          :components (tr/->Pointer frag-id {})
          :pages-index {page-id (tr/->Pointer frag-id {})}}})

(defn- cmd-stub
  [respond]
  (mock/stub
   (fn
     ([command] (respond command nil))
     ([command params] (respond command params)))))

(t/deftest ^:async resolve-file-resolves-all-fragments
  (let [seen (atom nil)]
    (await
     (mock/with-mocks*
       {rp/cmd! (cmd-stub (fn [command _params]
                            (t/is (= :get-file-fragment command))
                            (rx/of {:data fragment-content})))}
       (await (a/observe (fpmap/resolve-file (file-with-pointers))
                         :on-next #(reset! seen %)))))
    (t/is (some? @seen))
    (t/is (= fragment-content (get-in @seen [:data :components])))
    (t/is (= fragment-content (get-in @seen [:data :pages-index page-id])))))

(t/deftest ^:async resolve-file-rejects-on-fragment-failure
  (let [failure (ex-info "fragment exploded" {})
        seen    (atom ::none)
        caught  (atom nil)]
    (await
     (mock/with-mocks*
       {rp/cmd! (cmd-stub (fn [_command _params] (rx/throw failure)))}
       (await (a/observe (fpmap/resolve-file (file-with-pointers))
                         :on-next #(reset! seen %)
                         :on-error #(reset! caught %)))))
    ;; The page is never delivered half-resolved and the original cause
    ;; propagates instead of being swallowed.
    (t/is (= ::none @seen))
    (t/is (= failure @caught))))
