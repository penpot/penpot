;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.persistence
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.media :as cm]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.data.dashboard :as dd]
   [app.main.data.media :as di]
   [app.main.data.messages :as dm]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.svg-upload :as svg]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.avatars :as avatars]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [app.util.uri :as uu]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [promesa.core :as p]))

(declare persist-changes)
(declare persist-sychronous-changes)
(declare shapes-changes-persisted)
(declare update-persistence-status)

;; --- Persistence

(defn initialize-file-persistence
  [file-id]
  (ptk/reify ::initialize-persistence
    ptk/EffectEvent
    (effect [_ state stream]
      (let [stoper   (rx/filter #(= ::finalize %) stream)
            forcer   (rx/filter #(= ::force-persist %) stream)
            notifier (->> stream
                          (rx/filter (ptk/type? ::dwc/commit-changes))
                          (rx/debounce 2000)
                          (rx/merge stoper forcer))

            local-file? #(as-> (:file-id %) event-file-id
                           (or (nil? event-file-id)
                               (= event-file-id file-id)))
            library-file? #(as-> (:file-id %) event-file-id
                             (and (some? event-file-id)
                                  (not= event-file-id file-id)))

            on-dirty
            (fn []
              ;; Enable reload stoper
              (obj/set! js/window "onbeforeunload" (constantly false))
              (st/emit! (update-persistence-status {:status :pending})))

            on-saving
            (fn []
              (st/emit! (update-persistence-status {:status :saving})))

            on-saved
            (fn []
              ;; Disable reload stoper
              (obj/set! js/window "onbeforeunload" nil)
              (st/emit! (update-persistence-status {:status :saved})))]
        (->> (rx/merge
               (->> stream
                    (rx/filter (ptk/type? ::dwc/commit-changes))
                    (rx/map deref)
                    (rx/filter local-file?)
                    (rx/tap on-dirty)
                    (rx/buffer-until notifier)
                    (rx/filter (complement empty?))
                    (rx/map (fn [buf] {:file-id file-id
                                       :changes (into [] (mapcat :changes) buf)}))
                    (rx/map persist-changes)
                    (rx/tap on-saving)
                    (rx/take-until (rx/delay 100 stoper)))
               (->> stream
                    (rx/filter (ptk/type? ::dwc/commit-changes))
                    (rx/map deref)
                    (rx/filter library-file?)
                    (rx/filter (complement #(empty? (:changes %))))
                    (rx/map persist-sychronous-changes)
                    (rx/take-until (rx/delay 100 stoper)))
               (->> stream
                    (rx/filter (ptk/type? ::changes-persisted))
                    (rx/tap on-saved)
                    (rx/ignore)
                    (rx/take-until stoper)))
             (rx/subs #(st/emit! %)
                      (constantly nil)
                      (fn []
                        (on-saved))))))))

(defn persist-changes
  [{:keys [file-id changes]}]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::persist-changes
    ptk/UpdateEvent
    (update [_ state]
      (let [conj (fnil conj [])
            chng {:id (uuid/next)
                  :changes changes}]
        (update-in state [:workspace-persistence :queue] conj chng)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [sid     (:session-id state)
            file    (get state :workspace-file)
            queue   (get-in state [:workspace-persistence :queue] [])

            xf-cat  (comp (mapcat :changes))
            params  {:id (:id file)
                     :revn (:revn file)
                     :session-id sid
                     :changes (into [] xf-cat queue)}

            ids     (into #{} (map :id) queue)

            update-persistence-queue
            (fn [state]
              (update-in state [:workspace-persistence :queue]
                         (fn [items] (into [] (remove #(ids (:id %))) items))))

            handle-response
            (fn [lagged]
              (let [lagged (cond->> lagged
                             (= #{sid} (into #{} (map :session-id) lagged))
                             (map #(assoc % :changes [])))]
                (rx/concat
                 (rx/of update-persistence-queue)
                 (->> (rx/of lagged)
                      (rx/mapcat seq)
                      (rx/map #(shapes-changes-persisted file-id %))))))

            on-error
            (fn [{:keys [type] :as error}]
              (if (or (= :bad-gateway type)
                      (= :service-unavailable type))
                (rx/of (update-persistence-status {:status :error :reason type}))
                (rx/concat
                 (rx/of update-persistence-queue)
                 (rx/of (update-persistence-status {:status :error :reason type}))
                 (rx/of (dws/deselect-all))
                 (->> (rx/of nil)
                      (rx/delay 200)
                      (rx/mapcat #(rx/throw error))))))]

        (when (= file-id (:id params))
          (->> (rp/mutation :update-file params)
               (rx/mapcat handle-response)
               (rx/catch on-error)))))))

(defn persist-sychronous-changes
  [{:keys [file-id changes]}]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::persist-synchronous-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [sid     (:session-id state)
            file    (get-in state [:workspace-libraries file-id])

            params  {:id (:id file)
                     :revn (:revn file)
                     :session-id sid
                     :changes changes}]

        (when (:id params)
          (->> (rp/mutation :update-file params)
               (rx/ignore)))))))


(defn update-persistence-status
  [{:keys [status reason]}]
  (ptk/reify ::update-persistence-status
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-persistence
              (fn [local]
                (assoc local
                       :reason reason
                       :status status
                       :updated-at (dt/now)))))))

(s/def ::shapes-changes-persisted
  (s/keys :req-un [::revn ::cp/changes]))

(defn shapes-changes-persisted
  [file-id {:keys [revn changes] :as params}]
  (us/verify ::us/uuid file-id)
  (us/verify ::shapes-changes-persisted params)
  (ptk/reify ::changes-persisted
    ptk/UpdateEvent
    (update [_ state]
      (if (= file-id (:current-file-id state))
        (-> state
            (update-in [:workspace-file :revn] max revn)
            (update :workspace-data cp/process-changes changes)
            (update-in [:workspace-file :data] cp/process-changes changes))
        (-> state
            (update-in [:workspace-libraries file-id :revn] max revn)
            (update-in [:workspace-libraries file-id :data]
                       cp/process-changes changes))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Fetching & Uploading
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::name string?)
(s/def ::type keyword?)
(s/def ::file-id ::us/uuid)
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)
(s/def ::version ::us/integer)
(s/def ::revn ::us/integer)
(s/def ::ordering ::us/integer)
(s/def ::data ::cp/data)

(s/def ::file ::dd/file)
(s/def ::project ::dd/project)
(s/def ::page
  (s/keys :req-un [::id
                   ::name
                   ::file-id
                   ::revn
                   ::created-at
                   ::modified-at
                   ::ordering
                   ::data]))

(declare fetch-libraries-content)
(declare bundle-fetched)

(defn- fetch-bundle
  [project-id file-id]
  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/zip (rp/query :file {:id file-id})
                   (rp/query :team-users {:file-id file-id})
                   (rp/query :project {:id project-id})
                   (rp/query :file-libraries {:file-id file-id}))
           (rx/first)
           (rx/map (fn [bundle] (apply bundle-fetched bundle)))))))

(defn- bundle-fetched
  [file users project libraries]
  (ptk/reify ::bundle-fetched
    IDeref
    (-deref [_]
      {:file file
       :users users
       :project project
       :libraries libraries})

    ptk/UpdateEvent
    (update [_ state]
      (assoc state
             :users (d/index-by :id users)
             :workspace-undo {}
             :workspace-project project
             :workspace-file file
             :workspace-data (:data file)
             :workspace-libraries (d/index-by :id libraries)))))


;; --- Set File shared

(defn set-file-shared
  [id is-shared]
  {:pre [(uuid? id) (boolean? is-shared)]}
  (ptk/reify ::set-file-shared
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-file :is-shared] is-shared))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :is-shared is-shared}]
        (->> (rp/mutation :set-file-shared params)
             (rx/ignore))))))


;; --- Fetch Shared Files

(declare shared-files-fetched)

(defn fetch-shared-files
  [{:keys [team-id] :as params}]
  (us/assert ::us/uuid team-id)
  (ptk/reify ::fetch-shared-files
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :shared-files params)
           (rx/map shared-files-fetched)))))

(defn shared-files-fetched
  [files]
  (us/verify (s/every ::file) files)
  (ptk/reify ::shared-files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [state (dissoc state :files)]
        (assoc state :workspace-shared-files files)))))


;; --- Link and unlink Files

(defn link-file-to-library
  [file-id library-id]
  (ptk/reify ::link-file-to-library
    ptk/WatchEvent
    (watch [_ state stream]
      (let [fetched #(assoc-in %2 [:workspace-libraries (:id %1)] %1)
            params  {:file-id file-id
                     :library-id library-id}]
        (->> (rp/mutation :link-file-to-library params)
             (rx/mapcat #(rp/query :file {:id library-id}))
             (rx/map #(partial fetched %)))))))

(defn unlink-file-from-library
  [file-id library-id]
  (ptk/reify ::unlink-file-from-library
    ptk/WatchEvent
    (watch [_ state stream]
      (let [unlinked #(d/dissoc-in % [:workspace-libraries library-id])
            params   {:file-id file-id
                      :library-id library-id}]
        (->> (rp/mutation :unlink-file-from-library params)
             (rx/map (constantly unlinked)))))))

;; --- Fetch Pages

(declare page-fetched)

(defn fetch-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::fetch-pages
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :page {:id page-id})
           (rx/map page-fetched)))))

(defn page-fetched
  [{:keys [id] :as page}]
  (us/verify ::page page)
  (ptk/reify ::page-fetched
    IDeref
    (-deref [_] page)

    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-pages id] page))))


;; --- Upload File Media objects

(defn parse-svg
  [[name text]]
  (->> (rp/query! :parsed-svg {:data text})
       (rx/map #(assoc % :name name))))

(defn fetch-svg [name uri]
  (->> (http/send! {:method :get :uri uri :mode :no-cors})
       (rx/map #(vector
                 (or name (uu/uri-name uri))
                 (:body %)))))

(defn- handle-upload-error
  "Generic error handler for all upload methods."
  [on-error stream]
  (letfn [(on-error* [error]
            (if (ex/ex-info? error)
              (on-error* (ex-data error))
              (cond
                (= (:code error) :invalid-svg-file)
                (rx/of (dm/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :media-type-not-allowed)
                (rx/of (dm/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :ubable-to-access-to-url)
                (rx/of (dm/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :invalid-image)
                (rx/of (dm/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :media-too-large)
                (rx/of (dm/error (tr "errors.media-too-large")))

                (= (:code error) :media-type-mismatch)
                (rx/of (dm/error (tr "errors.media-type-mismatch")))

                (= (:code error) :unable-to-optimize)
                (rx/of (dm/error (:hint error)))

                (fn? on-error)
                (on-error error)

                :else
                (rx/throw error))))]
    (rx/catch on-error* stream)))

(defn- process-uris
  [{:keys [file-id local? name uris mtype on-image on-svg]}]
  (letfn [(svg-url? [url]
            (or (and mtype (= mtype "image/svg+xml"))
                (str/ends-with? url ".svg")))

          (prepare [uri]
            {:file-id file-id
             :is-local local?
             :name (or name (uu/uri-name uri))
             :url uri})]
    (rx/merge
     (->> (rx/from uris)
          (rx/filter (comp not svg-url?))
          (rx/map prepare)
          (rx/mapcat #(rp/mutation! :create-file-media-object-from-url %))
          (rx/do on-image))

     (->> (rx/from uris)
          (rx/filter svg-url?)
          (rx/merge-map (partial fetch-svg name))
          (rx/merge-map parse-svg)
          (rx/do on-svg)))))

(defn- process-blobs
  [{:keys [file-id local? name blobs force-media on-image on-svg]}]
  (letfn [(svg-blob? [blob]
            (and (not force-media)
                 (= (.-type blob) "image/svg+xml")))

          (prepare-blob [blob]
            (let [name (or name (if (di/file? blob) (.-name blob) "blob"))]
              {:file-id file-id
               :name name
               :is-local local?
               :content blob}))

          (extract-content [blob]
            (let [name (or name (.-name blob))]
              (-> (.text ^js blob)
                  (p/then #(vector name %)))))]

    (rx/merge
     (->> (rx/from blobs)
          (rx/map di/validate-file)
          (rx/filter (comp not svg-blob?))
          (rx/map prepare-blob)
          (rx/mapcat #(rp/mutation! :upload-file-media-object %))
          (rx/do on-image))

     (->> (rx/from blobs)
          (rx/map di/validate-file)
          (rx/filter svg-blob?)
          (rx/merge-map extract-content)
          (rx/merge-map parse-svg)
          (rx/do on-svg)))))

(s/def ::local? ::us/boolean)
(s/def ::blobs ::di/blobs)
(s/def ::name ::us/string)
(s/def ::uris (s/coll-of ::us/string))
(s/def ::mtype ::us/string)

(s/def ::process-media-objects
  (s/and
   (s/keys :req-un [::file-id ::local?]
           :opt-in [::name ::data ::uris ::mtype])
   (fn [props]
     (or (contains? props :blobs)
         (contains? props :uris)))))

(defn- process-media-objects
  [{:keys [uris on-error] :as params}]
  (us/assert ::process-media-objects params)
  (ptk/reify ::process-media-objects
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/concat
       (rx/of (dm/show {:content (tr "media.loading")
                        :type :info
                        :timeout nil
                        :tag :media-loading}))
       (->> (if (seq uris)
              ;; Media objects is a list of URL's pointing to the path
              (process-uris params)
              ;; Media objects are blob of data to be upload
              (process-blobs params))

            ;; Every stream has its own sideffect. We need to ignore the result
            (rx/ignore)
            (handle-upload-error on-error)
            (rx/finalize (st/emitf (dm/hide-tag :media-loading))))))))

(defn upload-media-asset
  [params]
  (let [params (assoc params
                      :force-media true
                      :local? false
                      :on-image #(st/emit! (dwl/add-media %)))]
    (process-media-objects params)))

(defn upload-media-workspace
  [{:keys [position file-id] :as params}]
  (let [params (assoc params
                      :local? true
                      :on-image #(st/emit! (dwc/image-uploaded % position))
                      :on-svg   #(st/emit! (svg/svg-uploaded % file-id position)))]

    (process-media-objects params)))


;; --- Upload File Media objects

(s/def ::object-id ::us/uuid)

(s/def ::clone-media-objects-params
  (s/keys :req-un [::file-id ::object-id]))

(defn clone-media-object
  [{:keys [file-id object-id] :as params}]
  (us/assert ::clone-media-objects-params params)
   (ptk/reify ::clone-media-objects
     ptk/WatchEvent
     (watch [_ state stream]
       (let [{:keys [on-success on-error]
              :or {on-success identity
                   on-error identity}} (meta params)
             params {:is-local true
                     :file-id file-id
                     :id object-id}]

         (rx/concat
          (rx/of (dm/show {:content (tr "media.loading")
                           :type :info
                           :timeout nil
                           :tag :media-loading}))
          (->> (rp/mutation! :clone-file-media-object params)
               (rx/do on-success)
               (rx/catch on-error)
               (rx/finalize #(st/emit! (dm/hide-tag :media-loading)))))))))

;; --- Helpers

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (-> state
      (update-in [:workspace-file :pages] #(filterv (partial not= id) %))
      (update :workspace-pages dissoc id)))

