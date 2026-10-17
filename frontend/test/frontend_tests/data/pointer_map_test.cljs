;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.pointer-map-test
  "Unit tests for the fragment resolvers (workspace and viewer): resolved
  data comes back whole, and a failing fragment rejects instead of
  silently dropping the page."
  (:require
   [app.common.transit :as tr]
   [app.common.uuid :as uuid]
   [app.main.data.viewer :as dv]
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

(t/deftest ^:async resolve-viewer-fragment-rejects-with-original-cause
  (let [failure (ex-info "fragment exploded" {})
        seen    (atom ::none)
        caught  (atom nil)]
    (await
     (mock/with-mocks*
       {rp/cmd! (cmd-stub (fn [_command _params] (rx/throw failure)))}
       (await (a/observe (dv/resolve-viewer-fragment file-id nil [:k (tr/->Pointer frag-id {})])
                         :on-next #(reset! seen %)
                         :on-error #(reset! caught %)))))
    (t/is (= ::none @seen))
    (t/is (= failure @caught))))

(t/deftest ^:async resolve-viewer-fragment-pairs-key-with-data
  (let [seen (atom ::none)]
    (await
     (mock/with-mocks*
       {rp/cmd! (cmd-stub (fn [command params]
                            (t/is (= :get-file-fragment command))
                            (t/is (= {:file-id file-id :fragment-id frag-id} params))
                            (rx/of {:data fragment-content})))}
       (await (a/observe (dv/resolve-viewer-fragment file-id nil [:k (tr/->Pointer frag-id {})])
                         :on-next #(reset! seen %)))))
    (t/is (= [:k fragment-content] @seen))))

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
