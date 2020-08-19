;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.persistence
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [app.common.data :as d]
   [app.common.media :as cm]
   [app.common.geom.point :as gpt]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.media :as di]
   [app.main.data.workspace.common :as dwc]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.util.transit :as t]))

(declare persist-changes)
(declare update-selection-index)
(declare shapes-changes-persisted)

;; --- Persistence

(defn initialize-page-persistence
  [page-id]
  (letfn [(enable-reload-stoper []
            (obj/set! js/window "onbeforeunload" (constantly false)))
          (disable-reload-stoper []
            (obj/set! js/window "onbeforeunload" nil))]
    (ptk/reify ::initialize-persistence
      ptk/UpdateEvent
      (update [_ state]
        (assoc state :current-page-id page-id))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [stoper   (rx/filter #(= ::finalize %) stream)
              notifier (->> stream
                            (rx/filter (ptk/type? ::dwc/commit-changes))
                            (rx/debounce 2000)
                            (rx/merge stoper))]
          (rx/merge
           (->> stream
                (rx/filter (ptk/type? ::dwc/commit-changes))
                (rx/map deref)
                (rx/tap enable-reload-stoper)
                (rx/buffer-until notifier)
                (rx/map vec)
                (rx/filter (complement empty?))
                (rx/map #(persist-changes page-id %))
                (rx/take-until (rx/delay 100 stoper)))
           (->> stream
                (rx/filter (ptk/type? ::changes-persisted))
                (rx/tap disable-reload-stoper)
                (rx/ignore)
                (rx/take-until stoper))))))))

(defn persist-changes
  [page-id changes]
  (ptk/reify ::persist-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [sid     (:session-id state)
            page    (get-in state [:workspace-pages page-id])
            changes (into [] (mapcat identity) changes)
            params  {:id (:id page)
                     :revn (:revn page)
                     :session-id sid
                     :changes changes}]
        (->> (rp/mutation :update-page params)
             (rx/map (fn [lagged]
                       (if (= #{sid} (into #{} (map :session-id) lagged))
                         (map #(assoc % :changes []) lagged)
                         lagged)))
             (rx/mapcat seq)
             (rx/map shapes-changes-persisted))))))

(s/def ::shapes-changes-persisted
  (s/keys :req-un [::page-id ::revn ::cp/changes]))

(defn shapes-changes-persisted
  [{:keys [page-id revn changes] :as params}]
  (us/verify ::shapes-changes-persisted params)
  (ptk/reify ::changes-persisted
    ptk/UpdateEvent
    (update [_ state]
      (let [sid   (:session-id state)
            page  (get-in state [:workspace-pages page-id])
            state (update-in state [:workspace-pages page-id :revn] #(max % revn))]
        (-> state
            (update-in [:workspace-data page-id] cp/process-changes changes)
            (update-in [:workspace-pages page-id :data] cp/process-changes changes))))))


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
(s/def ::metadata (s/nilable ::cp/metadata))
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
                   (rp/query :file-users {:id file-id})
                   (rp/query :project-by-id {:project-id project-id})
                   (rp/query :pages {:file-id file-id})
                   (rp/query :media-objects {:file-id file-id :is-local false})
                   (rp/query :colors {:file-id file-id})
                   (rp/query :file-libraries {:file-id file-id}))
           (rx/first)
           (rx/mapcat
             (fn [bundle]
               (->> (fetch-libraries-content (get bundle 6))
                    (rx/map (fn [[lib-media-objects lib-colors]]
                              (conj bundle lib-media-objects lib-colors))))))
           (rx/map (fn [bundle]
                     (apply bundle-fetched bundle)))
           (rx/catch (fn [{:keys [type code] :as error}]
                       (cond
                         (= :not-found type)
                         (rx/of (rt/nav' :not-found))

                         (and (= :authentication type)
                              (= :unauthorized code))
                         (rx/of (rt/nav' :not-authorized))

                         :else
                         (throw error))))))))

(defn- fetch-libraries-content
  [libraries]
  (if (empty? libraries)
    (rx/of [{} {}])
    (rx/zip
      (->> ;; fetch media-objects list of each library, and concatenate in a sequence
           (apply rx/zip (for [library libraries]
                           (->> (rp/query :media-objects {:file-id (:id library)
                                                          :is-local false})
                                (rx/map (fn [media-objects]
                                          [(:id library) media-objects])))))

           ;; reorganize the sequence as a map {library-id -> media-objects}
           (rx/map (fn [media-list]
                     (reduce (fn [result, [library-id media-objects]]
                               (assoc result library-id media-objects))
                             {}
                             media-list))))

      (->> ;; fetch colorss list of each library, and concatenate in a vector
           (apply rx/zip (for [library libraries]
                           (->> (rp/query :colors {:file-id (:id library)})
                                (rx/map (fn [colors]
                                          [(:id library) colors])))))

           ;; reorganize the sequence as a map {library-id -> colors}
           (rx/map (fn [colors-list]
                     (reduce (fn [result, [library-id colors]]
                               (assoc result library-id colors))
                             {}
                             colors-list)))))))

(defn- bundle-fetched
  [file users project pages media-objects colors libraries lib-media-objects lib-colors]
  (ptk/reify ::bundle-fetched
    IDeref
    (-deref [_]
      {:file file
       :users users
       :project project
       :pages pages
       :media-objects media-objects
       :colors colors
       :libraries libraries})

    ptk/UpdateEvent
    (update [_ state]
      (let [assoc-page
            #(assoc-in %1 [:workspace-pages (:id %2)] %2)

            assoc-media-objects
            #(assoc-in %1 [:workspace-libraries %2 :media-objects]
                       (get lib-media-objects %2))

            assoc-colors
            #(assoc-in %1 [:workspace-libraries %2 :colors]
                       (get lib-colors %2))]

        (as-> state $$
          (assoc $$
                 :workspace-file (assoc file
                                        :media-objects media-objects
                                        :colors colors)
                 :workspace-users (d/index-by :id users)
                 :workspace-pages {}
                 :workspace-project project
                 :workspace-libraries (d/index-by :id libraries))
          (reduce assoc-media-objects $$ (keys lib-media-objects))
          (reduce assoc-colors $$ (keys lib-colors))
          (reduce assoc-page $$ pages))))))


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

(declare file-linked)

(defn link-file-to-library
  [file-id library-id]
  (ptk/reify ::link-file-to-library
    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:file-id file-id
                    :library-id library-id}]
        (->> (->> (rp/mutation :link-file-to-library params)
                  (rx/mapcat
                    #(rx/zip (rp/query :file-library {:file-id library-id})
                             (rp/query :media-objects {:file-id library-id
                                                       :is-local false})
                             (rp/query :colors {:file-id library-id}))))
             (rx/map file-linked))))))

(defn file-linked
  [[library media-objects colors]]
  (ptk/reify ::file-linked
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-libraries (:id library)]
                (assoc library
                       :media-objects media-objects
                       :colors colors)))))

(declare file-unlinked)

(defn unlink-file-from-library
  [file-id library-id]
  (ptk/reify ::unlink-file-from-library
    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:file-id file-id
                    :library-id library-id}]
        (->> (rp/mutation :unlink-file-from-library params)
             (rx/map #(file-unlinked file-id library-id)))))))

(defn file-unlinked
  [file-id library-id]
  (ptk/reify ::file-unlinked
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:workspace-libraries library-id]))))


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


;; --- Page Crud

(declare page-created)

(def create-empty-page
  (ptk/reify ::create-empty-page
    ptk/WatchEvent
    (watch [this state stream]
      (let [file-id (get-in state [:workspace-file :id])
            name (name (gensym "Page "))
            ordering (count (get-in state [:workspace-file :pages]))
            params {:name name
                    :file-id file-id
                    :ordering ordering
                    :data cp/default-page-data}]
        (->> (rp/mutation :create-page params)
             (rx/map page-created))))))

(defn page-created
  [{:keys [id file-id] :as page}]
  (us/verify ::page page)
  (ptk/reify ::page-created
    cljs.core/IDeref
    (-deref [_] page)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-file :pages] (fnil conj []) id)
          (assoc-in [:workspace-pages id] page)))))

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [id name]
  (us/verify ::us/uuid id)
  (us/verify string? name)
  (ptk/reify ::rename-page
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace-page :id])
            state (assoc-in state [:workspace-pages id :name] name)]
        (cond-> state
          (= pid id) (assoc-in [:workspace-page :name] name))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :name name}]
        (->> (rp/mutation :rename-page params)
             (rx/map #(ptk/data-event ::page-renamed params)))))))

(declare purge-page)
(declare go-to-file)

(defn delete-page
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (purge-page state id))

    ptk/WatchEvent
    (watch [_ state s]
      (let [page (:workspace-page state)]
        (rx/merge
         (->> (rp/mutation :delete-page  {:id id})
              (rx/flat-map (fn [_]
                             (if (= id (:id page))
                               (rx/of go-to-file)
                               (rx/empty))))))))))


;; --- Upload local media objects

(s/def ::local? ::us/boolean)
(s/def ::uri ::us/string)

(s/def ::upload-media-objects-params
  (s/keys :req-un [::file-id ::local?]
          :opt-un [::uri ::di/js-files]))

(defn upload-media-objects
  [{:keys [file-id local? js-files uri] :as params}]
  (us/assert ::upload-media-objects-params params)
   (ptk/reify ::upload-media-objects
     ptk/WatchEvent
     (watch [_ state stream]
       (let [{:keys [on-success on-error]
              :or {on-success identity}} (meta params)

             is-library (not= file-id (:id (:workspace-file state)))
             prepare-js-file
             (fn [js-file]
               {:name (.-name js-file)
                :file-id file-id
                :content js-file
                :is-local local?})

             prepare-uri
             (fn [uri]
               {:file-id file-id
                :is-local local?
                :url uri})

             assoc-to-library
             (fn [media-object state]
               (cond
                 (true? local?)
                 state

                 (true? is-library)
                 (update-in state
                            [:workspace-libraries file-id :media-objects]
                            #(conj % media-object))

                 :else
                 (update-in state
                            [:workspace-file :media-objects]
                            #(conj % media-object))))]

         (rx/concat
          (rx/of (dm/show {:content (tr "media.loading")
                           :type :info
                           :timeout nil}))
          (->> (if (string? uri)
                 (->> (rx/of uri)
                      (rx/map prepare-uri)
                      (rx/mapcat #(rp/mutation! :add-media-object-from-url %)))
                 (->> (rx/from js-files)
                      (rx/map di/validate-file)
                      (rx/map prepare-js-file)
                      (rx/mapcat #(rp/mutation! :upload-media-object %))))
               (rx/do on-success)
               (rx/map (fn [mobj] (partial assoc-to-library mobj)))
               (rx/catch (fn [error]
                           (cond
                             (= (:code error) :media-type-not-allowed)
                             (rx/of (dm/error (tr "errors.media-type-not-allowed")))

                             (= (:code error) :media-type-mismatch)
                             (rx/of (dm/error (tr "errors.media-type-mismatch")))

                             (fn? on-error)
                             (do
                               (on-error error)
                               (rx/empty))

                             :else
                             (rx/throw error))))
               (rx/finalize (fn []
                              (st/emit! dm/hide)))))))))


;; --- Delete media object

(defn delete-media-object
  [file-id id]
  (ptk/reify ::delete-media-object
    ptk/UpdateEvent
    (update [_ state]
      (let [is-library (not= file-id (:id (:workspace-file state)))]
        (if is-library
          (update-in state
                     [:workspace-libraries file-id :media-objects]
                     (fn [media-objects] (filter #(not= (:id %) id) media-objects)))
          (update-in state
                     [:workspace-file :media-objects]
                     (fn [media-objects] (filter #(not= (:id %) id) media-objects))))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id}]
        (rp/mutation :delete-media-object params)))))


;; --- Helpers

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (-> state
      (update-in [:workspace-file :pages] #(filterv (partial not= id) %))
      (update :workspace-pages dissoc id)))

