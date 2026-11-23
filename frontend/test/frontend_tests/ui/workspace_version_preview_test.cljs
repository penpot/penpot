;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.workspace-version-preview-test
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/client" :as rdc]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dps]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.versions :as dwv]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.notifications :as notifications]
   [app.main.ui.workspace.version-controls :as controls]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.persistence :as thp]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(defn- ^js find-control
  [^js container selector label]
  (some #(when (= label (.-textContent ^js %)) %)
        (array-seq (js/Array.from (.querySelectorAll container selector)))))

(defn- ^:async with-mounted-version-controls
  [store test-fn]
  (let [dom          (JSDOM. "<!doctype html><html><body><div id='root'></div></body></html>")
        window       (.-window dom)
        document     (.-document window)
        old-window   (.-window js/globalThis)
        old-document (.-document js/globalThis)
        old-act      (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
        container    (.getElementById document "root")]
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (try
      (await
       ;; Other tests may replace st/state; the production refs retain the
       ;; original store. Point the refs and dispatch at this fixture.
       (mock/with-mocks* {notifications/ref:notification (l/derived :notification store)
                          controls/ref:controls (l/derived dwv/get-version-controls store)
                          refs/persistence (l/derived :persistence store)
                          st/state store
                          st/emit! (fn
                                     ([] nil)
                                     ([event] (ptk/emit! store event))
                                     ([event & events] (apply ptk/emit! store event events)))}
         (let [root (rdc/createRoot container)]
           (try
             (await (react/act #(.render root (mf/html
                                               [:*
                                                [:> notifications/current-notification*]
                                                [:> controls/version-controls* {:file-id (:current-file-id @store)}]]))))
             (await (test-fn container))
             (finally
               (await (react/act #(.unmount root))))))))
      (finally
        (.close window)
        (set! (.-window js/globalThis) old-window)
        (set! (.-document js/globalThis) old-document)
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) old-act)))))

(defn- wait-for-request
  [{:keys [requests requests$]} cmd]
  (async/wait-for #(some (fn [[request]] (= cmd request)) @requests)
                  (str cmd " reaches the transport")
                  {:signal requests$}))

(defn- open-live-file
  [{:keys [file-id store]} page-id shape-id name]
  (let [team-id (uuid/next)
        load-id (uuid/next)]
    (ptk/emit! store
               (thp/open-file file-id page-id (thp/file-data page-id shape-id name))
               #(-> %
                    (assoc :workspace-load-id load-id :current-team-id team-id)
                    (assoc-in [:files file-id :vern] 0)))))

(defn- get-file-response
  [file-id page-id shape-id name]
  {:id file-id :vern 0 :revn 0 :data (thp/file-data page-id shape-id name)})

(t/deftest ^:async a-rejected-save-keeps-the-report-and-the-preview-controls
  (let [save  (rx/subject)
        read  (rx/subject)
        snap  (rx/subject)
        flash errors/flash]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store] :as ctx}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               rename   (thp/rename-commit file-id page-id shape-id "Live" "Edited")
               name     #(thp/shape-name store file-id page-id shape-id)
               shown    (atom [])
               sub      (->> (ptk/input-stream store)
                             (rx/filter (ptk/type? ::dps/rolled-back))
                             (rx/subs! #(swap! shown conj (name))))]
           (try
             (open-live-file ctx page-id shape-id "Edited")
             (await
              (with-mounted-version-controls
                store
                (^:async fn [container]
                  (await
                   (mock/with-mocks* {errors/flash flash}
                     (await
                      (react/act
                       (^:async fn []
                         (ptk/emit! store rename ::dps/force-persist)
                         (await (wait-for-request ctx :update-file-v2))

                         (ptk/emit! store (dwv/enter-preview (uuid/next)))
                         (await (wait-for-request ctx :get-file-snapshot))
                         (rx/push! snap (get-file-response file-id page-id shape-id "Historical"))
                         (await (async/wait-for-store store #(= "Historical" (name)) "historical data"))

                         (rx/push! save (thp/rejection (:id @rename)))
                         (await (wait-for-request ctx :get-file))
                         (rx/push! read (get-file-response file-id page-id shape-id "Live"))
                         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                                  (seq (get-in @store [:notification :links])))
                                                      "the queue settles and the report is shown")))))

                     (t/is (= "Historical" (name)))
                     (t/is (empty? @shown))
                     (let [report-label (tr "labels.download" "report.txt")
                           exit         (find-control container "button" (tr "labels.exit"))
                           restore      (find-control container "button" (tr "labels.restore"))
                           report       (find-control container "a" report-label)]
                       (t/is (some? report))
                       (t/is (some? restore))
                       (t/is (some? exit))
                       (when (and exit restore report)
                         (doseq [control [exit restore report]]
                           (.focus ^js control)
                           (t/is (= control (.-activeElement (.-ownerDocument ^js container)))))
                         (await (react/act #(.click exit)))
                         (await (async/settle))
                         (t/is (= "Live" (name)))
                         (t/is (= ["Live"] @shown))
                         (t/is (nil? (find-control container "button" (tr "labels.exit"))))
                         (t/is (some? (find-control container "a" report-label))))))))))
             (finally
               (rx/dispose! sub)))))
       (fn [cmd _]
         (->> (case cmd
                :update-file-v2 save
                :get-file read
                :get-file-snapshot snap
                (rx/empty))
              (rx/take 1)
              (rx/observe-on :async)))))))

(t/deftest ^:async exiting-before-the-snapshot-arrives-keeps-the-live-model
  (let [snapshot (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store] :as ctx}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)]
           (open-live-file ctx page-id shape-id "Live")
           (await
            (with-mounted-version-controls
              store
              (^:async fn [container]
                (await
                 (react/act
                  (^:async fn []
                    (ptk/emit! store (dwv/enter-preview (uuid/next)))
                    (await (wait-for-request ctx :get-file-snapshot)))))
                (let [exit (find-control container "button" (tr "labels.exit"))]
                  (t/is (some? exit))
                  (when exit
                    (await (react/act #(.click exit)))
                    (t/is (nil? (find-control container "button" (tr "labels.exit"))))
                    (await
                     (react/act
                      (^:async fn []
                        (rx/push! snapshot (get-file-response file-id page-id shape-id "Historical"))
                        (await (async/settle)))))
                    (t/is (= "Live" (thp/shape-name store file-id page-id shape-id))))))))))
       (fn [cmd _]
         (if (= :get-file-snapshot cmd)
           (->> snapshot (rx/take 1) (rx/observe-on :async))
           (rx/empty)))))))

(defn- ^:async show-snapshot
  [{:keys [file-id store] :as ctx} snapshot snapshot-id page-id shape-id]
  (await
   (react/act
    (^:async fn []
      (ptk/emit! store (dwv/enter-preview snapshot-id))
      (await (wait-for-request ctx :get-file-snapshot))
      (rx/push! snapshot (get-file-response file-id page-id shape-id "Historical"))
      (await (async/wait-for-store store #(= "Historical" (thp/shape-name store file-id page-id shape-id))
                                   "historical data"))))))

(t/deftest ^:async reopening-the-file-discards-the-old-preview
  (let [snapshot (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store] :as ctx}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               team-id  (uuid/next)]
           (open-live-file ctx page-id shape-id "Live")
           (await
            (with-mounted-version-controls
              store
              (^:async fn [container]
                (await
                 (react/act
                  (^:async fn []
                    (ptk/emit! store (dwv/enter-preview (uuid/next)))
                    (await (wait-for-request ctx :get-file-snapshot))
                    (rx/push! snapshot (get-file-response file-id page-id shape-id "Historical"))
                    (await (async/wait-for-store store
                                                 #(= "Historical" (thp/shape-name store file-id page-id shape-id))
                                                 "the snapshot is displayed")))))
                (t/is (some? (find-control container "button" (tr "labels.exit"))))
                (await
                 (react/act
                  (^:async fn []
                    ;; Exercise the opening's state transition without
                    ;; starting the unrelated bundle and workspace watchers.
                    (ptk/emit! store #(ptk/update (dw/initialize-workspace team-id file-id) %))
                    (await (async/settle)))))
                (t/is (nil? (find-control container "button" (tr "labels.exit"))))
                (t/is (nil? (get-in @store [:workspace-global :preview-id])))
                (t/is (not (get-in @store [:workspace-global :read-only?])))
                (t/is (nil? (get-in @store [:workspace-versions :backup])))
                (t/is (= "Live" (thp/shape-name store file-id page-id shape-id))))))))
       (fn [cmd _]
         (if (= :get-file-snapshot cmd)
           (->> snapshot (rx/take 1) (rx/observe-on :async))
           (rx/empty)))))))

(defn- ^:async click-button
  [container label]
  (let [button (find-control container "button" label)]
    (t/is (some? button) (str "Button " label " is rendered"))
    (when button
      (await (react/act #(.click button))))))

(t/deftest ^:async a-repaired-save-notifies-once-during-preview-and-keeps-the-rejection-report
  (let [save     (rx/subject)
        read     (rx/subject)
        snapshot (rx/subject)
        flash    errors/flash]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id session-id requests requests$ store] :as ctx}]
         (let [page-id      (uuid/next)
               shape-id     (uuid/next)
               report-label (tr "labels.download" "report.txt")
               repaired     (tr "workspace.notifications.file-repaired")
               combined     (tr "workspace.notifications.file-repaired-changes-undone")
               notices      (atom [])
               requests-for (fn [cmd]
                              (into [] (keep (fn [[rpc {:keys [id] :as params}]]
                                               (when (and (= rpc cmd) (= id file-id)) params)))
                                    @requests))
               saved?       #(and (= :saved (get-in @store [:persistence :status]))
                                  (empty? (get-in @store [:persistence :queue])))
               rename       (fn [name]
                              (dch/commit {:file-id file-id :file-revn 0 :file-vern 0
                                           :features #{} :undo-group (uuid/next)
                                           :redo-changes [(thp/rename page-id shape-id name)]
                                           :undo-changes [(thp/rename page-id shape-id "Live")]}))
               rejected     (rename "Rejected")
               accepted     (rename "Saved")
               repair-row   {:id (uuid/next) :revn 2 :session-id (uuid/next)
                             :changes [(thp/set-attr page-id shape-id :opacity 0.7)]}
               answer       {:revn 0 :saved-revn 2
                             :repair (select-keys repair-row [:id :revn :changes])
                             :lagged [{:id (uuid/next) :revn 1 :session-id session-id
                                       :changes (:redo-changes @accepted)}
                                      repair-row]}
               sub          (->> (ptk/input-stream store)
                                 (rx/filter (ptk/type? ::ntf/show))
                                 (rx/subs! (fn [_]
                                             (let [content (get-in @store [:notification :content])]
                                               (when (contains? #{repaired combined} content)
                                                 (swap! notices conj content))))))]
           (try
             (open-live-file ctx page-id shape-id "Live")
             (await
              (with-mounted-version-controls
                store
                (^:async fn [container]
                  (await
                   (mock/with-mocks* {errors/flash flash}
                     (await
                      (react/act
                       (^:async fn []
                         (ptk/emit! store rejected ::dps/force-persist)
                         (await (async/wait-for #(= 1 (count (requests-for :update-file-v2)))
                                                "the first edit is sent" {:signal requests$}))
                         (rx/push! save (assoc (thp/rejection (:id @rejected)) :lagged []))
                         (await (async/wait-for-store store #(and (saved?) (seq (get-in @store [:notification :links])))
                                                      "the rejected save settles and shows its report")))))
                     (let [report-links (get-in @store [:notification :links])]
                       (t/is (some? (find-control container "a" report-label)))
                       (await
                        (react/act
                         (^:async fn []
                           (ptk/emit! store accepted ::dps/force-persist)
                           (await (async/wait-for #(= 2 (count (requests-for :update-file-v2)))
                                                  "the second edit is sent" {:signal requests$})))))
                       (t/is (true? (:repair-in-response (peek (requests-for :update-file-v2)))))
                       (await (show-snapshot ctx snapshot (uuid/next) page-id shape-id))
                       (let [historical-data (get-in @store [:files file-id :data])]
                         (await
                          (react/act
                           (^:async fn []
                             (rx/push! save answer)
                             (await (async/wait-for #(seq (requests-for :get-file))
                                                    "the accepted repair requests the live file"
                                                    {:signal requests$})))))
                         (t/is (empty? @notices) "The repair is not announced before the live file arrives")
                         (t/is (not (saved?)) "The confirmed save waits for the live file")
                         (await
                          (react/act
                           (^:async fn []
                             (rx/push! read {:id file-id :vern 0 :revn 2
                                             :data (thp/file-data page-id shape-id "Saved" :opacity 0.7)})
                             (await (async/wait-for-store store saved? "the repaired save settles")))))
                         (t/is (= historical-data (get-in @store [:files file-id :data])))
                         (t/is (= [combined] @notices))
                         (t/is (some? (find-control container "[role='alert'] div" combined)))
                         (t/is (= report-links (get-in @store [:notification :links])))
                         (t/is (some? (find-control container "a" report-label)))
                         (t/is (some? (find-control container "button" (tr "labels.restore"))))
                         (await (click-button container (tr "labels.exit")))
                         (await (react/act async/settle))
                         (t/is (nil? (find-control container "button" (tr "labels.exit"))))
                         (t/is (= "Saved" (thp/shape-name store file-id page-id shape-id)))
                         (t/is (= 0.7 (thp/shape-attr store file-id page-id shape-id :opacity)))
                         (t/is (= [combined] @notices) "Exit does not announce the repair again")
                         (t/is (some? (find-control container "a" report-label)))
                         (t/is (= [[(:id @rejected)] [(:id @accepted)]]
                                  (mapv #(mapv :commit-id (:commits %)) (requests-for :update-file-v2))))
                         (t/is (= 1 (count (requests-for :get-file)))))))))))
             (finally
               (rx/dispose! sub)))))
       (fn [cmd _]
         (->> (case cmd
                :update-file-v2 save
                :get-file read
                :get-file-snapshot snapshot
                (rx/empty))
              (rx/take 1)
              (rx/observe-on :async)))))))

(defn- restore-requests
  [requests]
  (filterv #(= :restore-file-snapshot (first %)) @requests))

(t/deftest ^:async leaving-preview-keeps-the-inspect-read-only-mode
  (let [snapshot (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store] :as ctx}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)]
           (open-live-file ctx page-id shape-id "Live")
           (ptk/emit! store #(assoc % :workspace-global {:read-only? true :options-mode :inspect}))
           (await
            (with-mounted-version-controls
              store
              (^:async fn [container]
                (await (show-snapshot ctx snapshot (uuid/next) page-id shape-id))
                (await (click-button container (tr "labels.exit")))
                (t/is (= "Live" (thp/shape-name store file-id page-id shape-id)))
                (t/is (nil? (get-in @store [:workspace-global :preview-id])))
                (t/is (true? (get-in @store [:workspace-global :read-only?]))))))))
       (fn [cmd _]
         (if (= :get-file-snapshot cmd)
           (->> snapshot (rx/take 1) (rx/observe-on :async))
           (rx/empty)))))))

(t/deftest ^:async dismissing-restore-returns-to-live-without-restoring-the-snapshot
  (let [snapshot (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id requests store] :as ctx}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)]
           (open-live-file ctx page-id shape-id "Live")
           (await
            (with-mounted-version-controls
              store
              (^:async fn [^js container]
                (await (show-snapshot ctx snapshot (uuid/next) page-id shape-id))
                (await (click-button container (tr "labels.restore")))
                (t/is (= (tr "workspace.versions.restore-warning")
                         (some-> (.querySelector container "aside > div") .-textContent)))
                (t/is (nil? (find-control container "button" (tr "labels.exit"))))
                (t/is (empty? (restore-requests requests)))
                (await (click-button container (tr "workspace.updates.dismiss")))
                (t/is (= "Live" (thp/shape-name store file-id page-id shape-id)))
                (t/is (nil? (find-control container "button" (tr "labels.restore"))))
                (t/is (empty? (restore-requests requests))))))))
       (fn [cmd _]
         (if (= :get-file-snapshot cmd)
           (->> snapshot (rx/take 1) (rx/observe-on :async))
           (rx/empty)))))))

(t/deftest ^:async confirming-restore-sends-one-request-and-stays-read-only-until-the-file-loads
  (let [snapshot (rx/subject)
        response (rx/subject)
        bundle   (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id requests store] :as ctx}]
         (let [page-id     (uuid/next)
               shape-id    (uuid/next)
               snapshot-id (uuid/next)
               restore     (tr "labels.restore")]
           (open-live-file ctx page-id shape-id "Live")
           (await
            (with-mounted-version-controls
              store
              (^:async fn [container]
                (await (show-snapshot ctx snapshot snapshot-id page-id shape-id))
                (await (click-button container restore))
                (t/is (empty? (restore-requests requests)))
                (let [confirm (find-control container "button" restore)]
                  (t/is (some? confirm))
                  (when confirm
                    (await (react/act #(do (.click confirm) (.click confirm))))
                    (t/is (= [[:restore-file-snapshot {:file-id file-id :id snapshot-id}]]
                             (restore-requests requests)))
                    (t/is (nil? (find-control container "button" restore)))
                    (await
                     (react/act
                      (^:async fn []
                        (rx/push! response nil)
                        (await (wait-for-request ctx :get-file)))))
                    (t/is (true? (get-in @store [:workspace-global :read-only?])))
                    (t/is (= "Historical" (thp/shape-name store file-id page-id shape-id)))
                    (await
                     (react/act
                      (^:async fn []
                        (rx/push! bundle (assoc (get-file-response file-id page-id shape-id "Restored") :vern 1))
                        (await (async/wait-for-store store #(nil? (get-in @store [:workspace-global :preview-id]))
                                                     "the restored file becomes live")))))
                    (t/is (not (get-in @store [:workspace-global :read-only?])))
                    (t/is (= "Restored" (thp/shape-name store file-id page-id shape-id)))
                    (t/is (nil? (find-control container "button" restore))))))))))
       (fn [cmd _]
         (->> (case cmd
                :get-file-snapshot snapshot
                :restore-file-snapshot response
                :get-file bundle
                :get-file-object-thumbnails (rx/of [])
                (rx/empty))
              (rx/take 1)
              (rx/observe-on :async)))))))
