;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.worker.component-thumbnails-test
  "The worker batches the component thumbnails the main thread asks for,
  renders the batches one at a time, sends each render back and uploads
  the ones asked for."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.thumbnails :as thc]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.fonts :as fonts]
   [app.util.http :as http]
   [app.util.webapi :as wapi]
   [app.worker.component-thumbnails :as wct]
   [app.worker.impl :as impl]
   [app.worker.wasm :as wwasm]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private file-id (uuid/next))
(def ^:private page-id (uuid/next))

(defn- request-render
  [root-id]
  (impl/handler {:cmd :thumbnails/render-component
                 :file-id file-id
                 :page-id page-id
                 :root-id root-id
                 :objects {}}
                nil))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(t/deftest ^:async a-burst-of-requests-renders-as-one-batch
  (let [jobs (atom 0)]
    (await
     (mock/with-mocks*
       {wwasm/submit (mock/stub (fn [_] (swap! jobs inc) (rx/empty)))}
       (run! request-render (repeatedly 30 uuid/next))
       (await (async/wait-for #(pos? @jobs) "the batch"))
       ;; Longer than the pause that would start a second batch
       (await (sleep 400))))
    (t/is (= 1 @jobs))))

(defn- ^:async render-with-module
  "Renders a batch of one main with the WASM module mocked as `module`.
  Returns what came out and whether it ended."
  [module]
  (let [out (atom {:values [] :ended? false :error nil})]
    (await
     (mock/with-mocks*
       {wwasm/module module}
       (await (async/observe (#'wct/render-batch [{:root-id (uuid/next) :objects {}}])
                             :on-next #(swap! out update :values conj %)
                             :on-error #(swap! out assoc :error %)
                             :on-complete #(swap! out assoc :ended? true)))))
    @out))

(t/deftest ^:async a-failed-render-ends-its-batch-quietly
  ;; A failed module load, and a failed job: the tests have no WASM render
  (doseq [module [(delay (js/Promise.reject (ex-info "no module" {})))
                  (delay (js/Promise.resolve nil))]]
    (t/is (= {:values [] :ended? true :error nil}
             (await (render-with-module module))))))

(t/deftest ^:async a-render-is-shown-and-uploaded-when-asked
  (let [output   (rx/subject)
        shown    (atom [])
        requests (atom [])
        file-id  (uuid/next)
        page-id  (uuid/next)
        edited   (uuid/next)
        undone   (uuid/next)
        blob     (wapi/create-blob "png" "image/png")]
    (rx/sub! output #(swap! shown conj %))
    (reset! @#'wct/output* output)
    (await
     (mock/with-mocks*
       {http/send! (mock/stub (fn [request] (swap! requests conj request) (rx/of {:status 200})))}
       (#'wct/add-render {:file-id file-id :page-id page-id :root-id edited :blob blob :persist? true})
       (#'wct/add-render {:file-id file-id :page-id page-id :root-id undone :blob blob :persist? false})))
    (reset! @#'wct/output* nil)
    (t/is (= [{:object-id (thc/fmt-object-id file-id page-id edited "component") :blob blob}
              {:object-id (thc/fmt-object-id file-id page-id undone "component") :blob blob}]
             @shown))
    (t/is (= [{:file-id file-id
               :object-id (thc/fmt-object-id file-id page-id edited "component")
               :tag "component"}]
             (mapv :query @requests)))))

(t/deftest an-out-of-date-main-is-set-before-it-renders
  (cthi/reset-idmap!)
  (let [file    (-> (cthf/sample-file :file)
                    (ctho/add-frame :root)
                    (ctht/apply-token-to-shape :root "radius" [:r1] [:r1] 10)
                    (cths/update-shape :root :token-values {:r1 10}))
        objects (:objects (cthf/current-page file))
        root-id (cthi/id :root)
        request {:page-id (cthf/current-page-id file)
                 :root-id root-id
                 :objects (select-keys objects [root-id])}]
    (t/is (= 50 (get-in (#'wct/set-token-values (assoc request :token-values {"radius" 50}))
                        [:objects root-id :r1])))
    (t/is (= request (#'wct/set-token-values request)) "without values it renders as it came")))

(t/deftest ^:async a-main-is-set-with-the-fonts-of-the-team
  (cthi/reset-idmap!)
  (let [font    {:id "custom-brand" :family "Brand" :variants [{:id "regular" :weight "400" :style "normal"}]}
        file    (-> (cthf/sample-file :file)
                    (ctho/add-text :root "Hello")
                    (ctht/apply-token-to-shape :root "family" [:font-family] [:font-family] "sourcesanspro")
                    (cths/update-shape :root :token-values {:font-family ["sourcesanspro"]}))
        objects (:objects (cthf/current-page file))
        root-id (cthi/id :root)
        batches (atom [])]
    (await
     (mock/with-mocks*
       {wwasm/submit (mock/stub (fn [f] (swap! batches conj f) (rx/empty)))
        wct/render-batch (mock/stub identity)}
       (impl/handler {:cmd :thumbnails/render-component
                      :file-id file-id
                      :page-id (cthf/current-page-id file)
                      :root-id root-id
                      :objects (select-keys objects [root-id])
                      :token-values {"family" ["Brand"]}
                      :custom-fonts [font]}
                     nil)
       (await (async/wait-for #(seq @batches) "the batch"))))
    (let [[{:keys [objects]}] ((first @batches))]
      (t/is (every? #(= "custom-brand" (:font-id %))
                    (txt/node-seq txt/is-text-node? (get-in objects [root-id :content])))))
    (fonts/register! :custom [])))
