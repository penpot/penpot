;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-token-context-test
  (:require
   [app.common.time :as ct]
   [app.common.transit :as transit]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.persistence :as dps]
   [app.main.data.persistence.order :as porder]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.websocket :as dws]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.notifications :as dwntf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.versions :as dwv]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.persistence :as thp]
   [potok.v2.core :as ptk]))

(defn- get-opacity
  [{:keys [store file-id page-id shape-id]}]
  (thp/shape-attr store file-id page-id shape-id :opacity))

(defn- ^:async enter-preview
  [{:keys [file-id store page-id shape-id requests requests$ history]} opacity]
  (let [requested? (fn [[cmd _]] (= cmd :get-file-snapshot))
        n          (count (filter requested? @requests))]
    (ptk/emit! store (dwv/enter-preview (uuid/next)))
    (await (async/wait-for #(> (count (filter requested? @requests)) n)
                           "the snapshot request starts" {:signal requests$}))
    (rx/push! history {:id file-id :vern 4 :revn 0
                       :data (thp/file-data page-id shape-id "Historical" :opacity opacity)})
    (await (async/wait-for-store store #(= "Historical" (thp/shape-name store file-id page-id shape-id))
                                 "the snapshot is visible"))))

(defn- ^:async recover-file
  [{:keys [file-id store requests requests$ read]} revn data & {:keys [preview unmarked]}]
  (let [get-file? (fn [[command params]] (and (= :get-file command) (= file-id (:id params))))
        n         (count (filter get-file? @requests))
        path      (if preview [:workspace-versions :backup] [:files file-id])]
    (ptk/emit! store
               (ptk/data-event ::dws/message
                               (cond-> {:type :file-change :topic file-id :file-id file-id
                                        :profile-id (uuid/next) :session-id (uuid/next)
                                        :vern 4 :revn revn :changes [] :revision-ids [(uuid/next)]}
                                 (not unmarked) (assoc :library-change true))))
    (await (async/wait-for #(> (count (filter get-file? @requests)) n)
                           "the marked message requires a live GET" {:signal requests$}))
    (rx/push! read {:id file-id :vern 4 :revn revn :data data :is-shared (not unmarked)})
    (await (async/wait-for-store store #(= revn (get-in @store (conj path :revn)))
                                 "the recovery GET installs"))))

(defn- ^:async recover-library
  [{:keys [store library-id requests requests$ read]} revn data]
  (let [requested? (fn [[cmd params]] (and (= cmd :get-file) (= library-id (:id params))))
        n          (count (filter requested? @requests))
        msg        {:type :library-change :file-id library-id :profile-id (uuid/next)
                    :session-id (uuid/next) :vern 7 :modified-at (ct/now) :changes []}]
    (ptk/emit! store
               (dwntf/handle-library-change (assoc msg :revn revn))
               (dwntf/handle-library-change (assoc msg :revn (dec revn) :repaired true)))
    (await (async/wait-for #(> (count (filter requested? @requests)) n)
                           "the late library repair requires a GET" {:signal requests$}))
    (rx/push! read {:id library-id :vern 7 :revn (inc revn) :data data :is-shared true
                    :modified-at (:modified-at msg)})
    (await (async/wait-for-store store #(= (inc revn) (get-in @store [:files library-id :revn]))
                                 "the library recovery GET installs"))))

(defn- ^:async with-delayed-token-resolution
  [mode scenario]
  (let [linked?     (contains? #{:linked :linked-preview} mode)
        external?   (or linked? (contains? #{:external :external-preview} mode))
        preview?    (contains? #{:preview :external-preview :linked-preview} mode)
        activation? (or (= :activation mode) (and external? (not linked?)))
        history     (rx/subject)
        read        (rx/subject)
        resolutions (atom [])
        posts       (atom [])
        post$       (rx/subject)
        send-save   (get-method rp/cmd! :default)
        prior-fetch js/fetch]
    (try
      (set! js/fetch
            (fn [url params]
              (let [post [url (.-method ^js params) (transit/decode-str (.-body ^js params))]]
                (swap! posts conj post)
                (rx/push! post$ post)
                (js/Promise.resolve
                 (js/Response. (transit/encode-str {:revn (:revn (peek post)) :lagged []})
                               #js {:status 200 :headers #js {"content-type" "application/transit+json"}})))))
      (await
       (mock/with-mocks*
         {cf/public-uri     (u/uri "https://penpot.example.com")
          cf/flags          (disj cf/flags :tokenscript)
          sd/resolve-tokens (mock/stub
                             (fn [tokens]
                               (rx/defer
                                (fn []
                                  (let [response (rx/subject)]
                                    (swap! resolutions conj {:tokens tokens :response response})
                                    (->> response (rx/take 1) (rx/observe-on :async)))))))}
         (await
          (thp/with-persistence
            (^:async fn [{:keys [file-id store] :as fixture}]
              (let [page-id   (uuid/next)
                    shape-id  (uuid/next)
                    load-id   (uuid/next)
                    set-id    (uuid/next)
                    other-id  (uuid/next)
                    token-id  (uuid/next)
                    library-id (when external? (uuid/next))
                    tokens    (-> (ctob/make-tokens-lib)
                                  (ctob/add-set (ctob/make-token-set :id set-id :name "Active"))
                                  (ctob/add-token set-id (ctob/make-token :id token-id :name "opacity"
                                                                          :type :opacity :value 0.2))
                                  (ctob/add-set (ctob/make-token-set :id other-id :name "Alternate"))
                                  (ctob/add-token other-id (ctob/make-token :name "opacity"
                                                                            :type :opacity :value 0.6)))
                    data      (cond-> (assoc (thp/file-data page-id shape-id "Live"
                                                            :opacity (if (or linked? (= :activation mode)) 0.4 0.2)
                                                            :applied-tokens {:opacity "opacity"})
                                             :id file-id
                                             :tokens-status (ctos/set-tokens-status
                                                             (ctos/make-tokens-status) #{} #{set-id}))
                                external? (assoc :tokens-source library-id)
                                (not external?) (assoc :tokens-lib tokens))
                    live-data (if activation?
                                (update data :tokens-status ctos/set-tokens-status #{} #{other-id})
                                (if linked?
                                  data
                                  (update data :tokens-lib ctob/update-token set-id token-id
                                          #(assoc % :value 0.6))))
                    library   (when external? {:id library-id :tokens-lib tokens})
                    changed-library (when linked?
                                      (update library :tokens-lib ctob/update-token set-id token-id
                                              #(assoc % :value 0.6)))
                    events    (atom [])
                    fixture   (assoc fixture :page-id page-id :shape-id shape-id :history history
                                     :base-data data :data live-data :read read :posts posts :post$ post$
                                     :resolutions resolutions :events events
                                     :library-id library-id :library library)
                    sub       (rx/subs! #(swap! events conj %) (ptk/input-stream store))]
                (try
                  (ptk/emit! store (thp/open-file file-id page-id data)
                             #(-> %
                                  (assoc :workspace-load-id load-id)
                                  (update-in [:files file-id] assoc :vern 4 :is-shared (not external?))
                                  (assoc-in [:persistence :file-order file-id] (porder/loaded 4 0))
                                  (cond-> external?
                                    (assoc-in [:files library-id]
                                              {:id library-id :vern 7 :revn 0 :data library
                                               :library-of file-id :synced-at (ct/now) :modified-at (ct/now)})))
                             (dwntf/initialize (uuid/next) file-id))
                  (when preview?
                    (await (enter-preview fixture 0.1)))
                  (if linked?
                    (await (recover-library fixture 2 changed-library))
                    (await (recover-file fixture 2 live-data :preview preview? :unmarked external?)))
                  (when preview?
                    (t/is (= 0.1 (get-opacity fixture)))
                    (t/is (empty? @resolutions) "the preview is not a token consumer")
                    (ptk/emit! store (dwv/exit-preview)))
                  (t/is (= [0.6] (mapv #(get-in % [:tokens "opacity" :value]) @resolutions))
                        "the reconciled live file starts token resolution")
                  (await (scenario fixture))
                  (finally (rx/dispose! sub)))))
            (fn [command params]
              (case command
                :get-file-snapshot (->> history (rx/take 1) (rx/observe-on :async))
                :get-file (->> read (rx/take 1) (rx/observe-on :async))
                :update-file-v2 (send-save command params)
                (rx/empty)))))))
      (finally
        (set! js/fetch prior-fetch)))))

(defn- ^:async release-resolution
  ([fixture] (await (release-resolution fixture 0)))
  ([{:keys [resolutions store]} index]
   (let [{:keys [response tokens]} (get @resolutions index)
         value (get-in tokens ["opacity" :value])]
     (when response
       (rx/push! response {"opacity" {:resolved-value value}})))
   (await (async/settle))
   (ptk/emit! store ::dps/force-persist)
   (await (async/settle))))

(defn- assert-no-token-edit
  [{:keys [store events posts]}]
  (t/is (not-any? dch/commit? @events) "no local change is committed")
  (t/is (empty? (get-in @store [:persistence :buffer])))
  (t/is (empty? (get-in @store [:persistence :queue])))
  (t/is (empty? @posts) "no stale save is sent even after a force flush")
  (t/is (not-any? (ptk/type? ::dwsh/update-shapes-buffer-start) @events)
        "stale results never open a shape buffer")
  (t/is (not-any? (ptk/type? ::dwu/start-undo-transaction) @events)
        "stale results never open an undo transaction"))

(defn- replace-file
  [{:keys [file-id page-id shape-id data]} vern opacity]
  (#'dw/bundle-fetched
   {:file-id file-id :thumbnails {}
    :file {:id file-id :vern vern :revn 2 :is-shared true
           :data (assoc-in data [:pages-index page-id :objects shape-id :opacity] opacity)}}))

(defn- ^:async assert-no-stale-edit-after-restore
  [{:keys [store] :as fixture}]
  (ptk/emit! store (replace-file fixture 5 0.9))
  (await (release-resolution fixture))
  (t/is (= 0.9 (get-opacity fixture)))
  (assert-no-token-edit fixture))

(t/deftest ^:async delayed-token-resolution-from-preview-cannot-write-into-a-restored-file
  (await (with-delayed-token-resolution :preview assert-no-stale-edit-after-restore)))

(t/deftest ^:async delayed-token-resolution-from-a-visible-read-cannot-write-into-a-restored-file
  (await (with-delayed-token-resolution :live assert-no-stale-edit-after-restore)))

(t/deftest ^:async delayed-token-resolution-cannot-write-into-another-opening-of-the-same-file
  (await
   (with-delayed-token-resolution :preview
     (^:async fn [{:keys [store] :as fixture}]
       (ptk/emit! store #(assoc % :workspace-load-id (uuid/next)) (replace-file fixture 4 0.8))
       (await (release-resolution fixture))
       (t/is (= 0.8 (get-opacity fixture)))
       (assert-no-token-edit fixture)))))

(t/deftest ^:async delayed-token-resolution-cannot-write-into-a-different-file
  (await
   (with-delayed-token-resolution :preview
     (^:async fn [{:keys [store] :as fixture}]
       (let [other-id (uuid/next)
             other    (assoc fixture :file-id other-id)]
         (ptk/emit! store #(assoc % :current-file-id other-id) (replace-file other 4 0.7))
         (await (release-resolution fixture))
         (t/is (= 0.7 (get-opacity other)))
         (t/is (= 0.2 (get-opacity fixture)))
         (assert-no-token-edit fixture))))))

(t/deftest ^:async delayed-token-resolution-cannot-run-during-another-preview
  (await
   (with-delayed-token-resolution :preview
     (^:async fn [fixture]
       (await (enter-preview fixture 0.3))
       (await (release-resolution fixture))
       (t/is (= 0.3 (get-opacity fixture)))
       (assert-no-token-edit fixture)))))

(t/deftest ^:async delayed-token-resolution-commits-and-saves-on-the-same-live-file
  (await
   (with-delayed-token-resolution :preview
     (^:async fn [{:keys [file-id store shape-id posts post$ events] :as fixture}]
       (await (release-resolution fixture))
       (await (async/wait-for #(seq @posts) "the token change is posted" {:signal post$}))
       (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                    "the save settles"))
       (t/is (= 0.6 (get-opacity fixture)))
       (t/is (= 1 (count (filter dch/commit? @events))))
       (t/is (= 1 (count @posts)))
       (let [[url method body] (first @posts)
             [commit]         (:commits body)
             [change]         (:redo-changes commit)]
         (t/is (= (str "https://penpot.example.com/api/main/methods/update-file-v2?id=" file-id) url))
         (t/is (= "POST" method))
         (t/is (= file-id (:id body)))
         (t/is (= 4 (:vern body)))
         (t/is (= shape-id (:id change)))
         (t/is (some #(and (= :opacity (:attr %)) (= 0.6 (:val %)))
                     (:operations change))))))))

(t/deftest ^:async a-late-resolution-cannot-save-the-token-from-a-previous-active-set
  (await
   (with-delayed-token-resolution :activation
     (^:async fn [{:keys [base-data store posts post$ resolutions events] :as fixture}]
       ;; The library is unchanged; the second GET only reactivates the original set.
       (await (recover-file fixture 4 base-data))
       (t/is (= [0.6 0.2] (mapv #(get-in % [:tokens "opacity" :value]) @resolutions)))
       (await (release-resolution fixture 1))
       (await (async/wait-for #(seq @posts) "the current token value is posted" {:signal post$}))
       (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                    "the current token save settles"))
       (t/is (= 0.2 (get-opacity fixture)))
       (await (release-resolution fixture 0))
       (t/is (= 0.2 (get-opacity fixture)))
       (t/is (= 1 (count (filter dch/commit? @events))))
       (t/is (= 1 (count (filter (ptk/type? ::dwu/start-undo-transaction) @events))))
       (t/is (= 1 (count @posts)) "the old calculation must not send another POST")
       (t/is (= [0.2] (for [[_ _ body] @posts
                            commit (:commits body)
                            change (:redo-changes commit)
                            op (:operations change)
                            :when (= :opacity (:attr op))]
                        (:val op))))))))

(t/deftest ^:async a-new-revision-with-the-same-token-input-keeps-a-recovery-resolution
  (await
   (with-delayed-token-resolution :live
     (^:async fn [{:keys [file-id store page-id shape-id data posts post$ resolutions] :as fixture}]
       (let [set-id  (ctob/get-id (first (ctob/get-sets (:tokens-lib data))))
             updated (-> data
                         (assoc-in [:pages-index page-id :objects shape-id :name] "Peer edit")
                         (update :tokens-lib ctob/update-set set-id #(ctob/set-description % "New description")))]
         (await (recover-file fixture 4 updated)))
       (t/is (= 1 (count @resolutions)) "the unrelated update does not start a second calculation")
       (await (release-resolution fixture 0))
       (await (async/wait-for #(seq @posts) "the still-current token value is posted" {:signal post$}))
       (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                    "the token save settles"))
       (t/is (= 0.6 (get-opacity fixture)))
       (t/is (= "Peer edit" (thp/shape-name store file-id page-id shape-id)))
       (t/is (= 1 (count @posts)))
       (t/is (= 4 (:revn (nth (first @posts) 2))))))))

(defn- ^:async assert-resolved-opacity-is-saved
  [{:keys [file-id store posts post$ events] :as fixture}]
  (await (release-resolution fixture))
  (await (async/wait-for #(seq @posts) "the recovered value is posted" {:signal post$}))
  (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                               "the consumer save settles"))
  (t/is (= 0.6 (get-opacity fixture)))
  (t/is (= 1 (count (filter dch/commit? @events))))
  (t/is (= [file-id] (mapv #(get-in % [2 :id]) @posts)) "only the consumer is saved"))

(t/deftest ^:async recovery-of-a-private-consumer-propagates-a-new-active-set-from-its-library
  (await (with-delayed-token-resolution :external assert-resolved-opacity-is-saved)))

(t/deftest ^:async recovery-of-an-external-active-set-propagates-on-leaving-preview
  (await (with-delayed-token-resolution :external-preview assert-resolved-opacity-is-saved)))

(t/deftest ^:async a-library-recovery-cannot-save-into-a-restored-consumer
  (await (with-delayed-token-resolution :linked assert-no-stale-edit-after-restore)))

(t/deftest ^:async a-library-recovered-during-preview-propagates-only-after-exit
  (await (with-delayed-token-resolution :linked-preview assert-resolved-opacity-is-saved)))

(t/deftest ^:async a-late-library-resolution-cannot-save-over-a-newer-recovery
  (await
   (with-delayed-token-resolution :linked
     (^:async fn [{:keys [library resolutions posts events] :as fixture}]
       (await (recover-library fixture 5 library))
       (t/is (= [0.6 0.2] (mapv #(get-in % [:tokens "opacity" :value]) @resolutions)))
       (await (release-resolution fixture 1))
       (t/is (= 0.2 (get-opacity fixture)))
       (await (release-resolution fixture 0))
       (t/is (= 0.2 (get-opacity fixture)))
       (t/is (= 1 (count (filter dch/commit? @events))))
       (t/is (= 1 (count @posts)) "only the calculation from the newest read is saved")))))

(t/deftest ^:async a-library-revision-with-the-same-token-input-keeps-the-pending-result
  (await
   (with-delayed-token-resolution :linked
     (^:async fn [{:keys [library library-id store resolutions] :as fixture}]
       (let [tokens  (:tokens-lib library)
             set-id  (ctob/get-id (first (ctob/get-sets tokens)))
             library (assoc library :tokens-lib
                            (ctob/update-set (get-in @store [:files library-id :data :tokens-lib])
                                             set-id #(ctob/set-description % "New description")))]
         (await (recover-library fixture 5 library)))
       (t/is (= 1 (count @resolutions)) "the newer metadata does not restart resolution")
       (await (assert-resolved-opacity-is-saved fixture))))))
