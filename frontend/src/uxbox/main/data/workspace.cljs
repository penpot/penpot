;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace
  (:require
   [clojure.set :as set]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.common.exceptions :as ex]
   [uxbox.config :as cfg]
   [uxbox.main.constants :as c]
   [uxbox.main.data.icons :as udi]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.websockets :as ws]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.math :as mth]
   [uxbox.util.perf :as perf]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]
   [uxbox.util.transit :as t]
   [uxbox.util.uuid :as uuid]
   [vendor.randomcolor]))

;; TODO: temporal workaround
(def clear-ruler nil)
(def start-ruler nil)

;; --- Specs

(s/def ::shape-attrs ::cp/shape-attrs)
(s/def ::set-of-uuid
  (s/every uuid? :kind set?))

;; --- Expose inner functions

(defn interrupt? [e] (= e :interrupt))

;; --- Protocols

(defprotocol IBatchedChange)

;; --- Declarations

(declare fetch-users)
(declare fetch-images)
(declare handle-who)
(declare handle-pointer-update)
(declare handle-pointer-send)
(declare handle-page-change)
(declare shapes-changes-commited)
(declare commit-changes)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace WebSocket
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialize WebSocket

(s/def ::type keyword?)
(s/def ::message
  (s/keys :req-un [::type]))

(defn initialize-ws
  [file-id]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [url (ws/url (str "/sub/" file-id))]
        (assoc-in state [:ws file-id] (ws/open url))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [wsession (get-in state [:ws file-id])
            stoper (rx/filter #(= ::finalize-ws %) stream)]
        (->> (rx/merge
              (->> (ws/-stream wsession)
                   (rx/filter #(= :message (:type %)))
                   (rx/map (comp t/decode :payload))
                   (rx/filter #(s/valid? ::message %))
                   (rx/map (fn [{:keys [type] :as msg}]
                             (case type
                               :who (handle-who msg)
                               :pointer-update (handle-pointer-update msg)
                               :page-change (handle-page-change msg)
                               ::unknown))))

              (->> stream
                   (rx/filter ms/pointer-event?)
                   (rx/sample 50)
                   (rx/map #(handle-pointer-send file-id (:pt %)))))

             (rx/take-until stoper))))))

;; --- Finalize Websocket

(defn finalize-ws
  [file-id]
  (ptk/reify ::finalize-ws
    ptk/WatchEvent
    (watch [_ state stream]
      (ws/-close (get-in state [:ws file-id]))
      (rx/of ::finalize-ws))))

;; --- Handle: Who

;; TODO: assign color

(defn- assign-user-color
  [state user-id]
  (let [user (get-in state [:workspace-users :by-id user-id])
        color (js/randomcolor)
        user (if (string? (:color user))
               user
               (assoc user :color color))]
    (assoc-in state [:workspace-users :by-id user-id] user)))

(defn handle-who
  [{:keys [users] :as msg}]
  (us/verify set? users)
  (ptk/reify ::handle-who
    ptk/UpdateEvent
    (update [_ state]
      (as-> state $$
        (assoc-in $$ [:workspace-users :active] users)
        (reduce assign-user-color $$ users)))))

(defn handle-pointer-update
  [{:keys [user-id page-id x y] :as msg}]
  (ptk/reify ::handle-pointer-update
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-users :pointer user-id]
                {:page-id page-id
                 :user-id user-id
                 :x x
                 :y y}))))

(defn handle-pointer-send
  [file-id point]
  (ptk/reify ::handle-pointer-update
    ptk/EffectEvent
    (effect [_ state stream]
      (let [ws (get-in state [:ws file-id])
            pid (get-in state [:workspace-page :id])
            msg {:type :pointer-update
                 :page-id pid
                 :x (:x point)
                 :y (:y point)}]
        (ws/-send ws (t/encode msg))))))

(defn handle-page-change
  [{:keys [profile-id page-id revn operations] :as msg}]
  (ptk/reify ::handle-page-change
    ptk/WatchEvent
    (watch [_ state stream]
      #_(let [page-id' (get-in state [:workspace-page :id])]
        (when (= page-id page-id')
          (rx/of (shapes-changes-commited msg)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Undo/Redo
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def MAX-UNDO-SIZE 50)

(defn- conj-undo-entry
  [undo data]
  (let [undo (conj undo data)]
    (if (> (count undo) MAX-UNDO-SIZE)
      (into [] (take MAX-UNDO-SIZE undo))
      undo)))

(defn- materialize-undo
  [changes index]
  (ptk/reify ::materialize-undo
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-data cp/process-changes changes)
          (assoc-in [:workspace-local :undo-index] index)))))

(defn- reset-undo
  [index]
  (ptk/reify ::reset-undo
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :undo-index)
          (update-in [:workspace-local :undo]
                     (fn [queue]
                       (into [] (take (inc index) queue))))))))

(s/def ::undo-changes ::cp/changes)
(s/def ::redo-changes ::cp/changes)
(s/def ::undo-entry
  (s/keys :req-un [::undo-changes ::redo-changes]))

(defn- append-undo
  [entry]
  (us/verify ::undo-entry entry)
  (ptk/reify ::append-undo
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :undo] (fnil conj-undo-entry []) entry))))

(def undo
  (ptk/reify ::undo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:workspace-local state)
            undo (:undo local [])
            index (or (:undo-index local)
                      (dec (count undo)))]
        (when-not (or (empty? undo) (= index -1))
          (let [changes (get-in undo [index :undo-changes])]
            (rx/of (materialize-undo changes (dec index))
                   (commit-changes changes [] {:save-undo? false}))))))))

(def redo
  (ptk/reify ::redo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:workspace-local state)
            undo (:undo local [])
            index (or (:undo-index local)
                      (dec (count undo)))]
        (when-not (or (empty? undo) (= index (dec (count undo))))
          (let [changes (get-in undo [(inc index) :redo-changes])]
            (rx/of (materialize-undo changes (inc index))
                   (commit-changes changes [] {:save-undo? false}))))))))

(def reinitialize-undo
  (ptk/reify ::reset-undo
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :undo-index :undo))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialize Workspace

(declare initialize-alignment)

(def default-layout #{:sitemap :layers :element-options :rules})

(def workspace-default
  {:zoom 1
   :flags #{}
   :selected #{}
   :drawing nil
   :drawing-tool nil
   :tooltip nil})

(declare initialize-layout)
(declare initialize-page)
(declare initialize-file)
(declare fetch-file-with-users)
(declare fetch-pages)
(declare fetch-page)

(defn initialize
  "Initialize the workspace state."
  [file-id page-id]
  (us/verify ::us/uuid file-id)
  (us/verify ::us/uuid page-id)
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (:workspace-file state)]
        (if (not= (:id file) file-id)
          (do
            ;; (reset! st/loader true)
            (rx/merge
             (rx/of (fetch-file-with-users file-id)
                    (fetch-pages file-id)
                    (initialize-layout file-id)
                    (fetch-images file-id))
             (->> (rx/zip (rx/filter (ptk/type? ::pages-fetched) stream)
                          (rx/filter (ptk/type? ::file-fetched) stream))
                  (rx/take 1)
                  (rx/do (fn [_]
                           (uxbox.util.timers/schedule 500 #(reset! st/loader false))))
                  (rx/mapcat (fn [_]
                               (rx/of (initialize-file file-id)
                                      (initialize-page page-id)
                                      #_(initialize-alignment page-id)))))))

          (rx/merge
           (rx/of (fetch-page page-id))
           (->> stream
                (rx/filter (ptk/type? ::pages-fetched))
                (rx/take 1)
                (rx/merge-map (fn [_]
                                (rx/of (initialize-file file-id)
                                       (initialize-page page-id)))))))))))

(defn- initialize-layout
  [file-id]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::initialize-layout
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-layout default-layout))))

(defn- initialize-file
  [file-id]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::initialize-file
    ptk/UpdateEvent
    (update [_ state]
      (let [file (get-in state [:files file-id])]
        (assoc state :workspace-file file)))))

(declare diff-and-commit-changes)

(defn initialize-page
  [page-id]
  (ptk/reify ::initialize-page
    ptk/UpdateEvent
    (update [_ state]
      ;; (prn "initialize-page" page-id)
      (let [page (get-in state [:pages page-id])
            data (get-in state [:pages-data page-id])
            local (get-in state [:workspace-cache page-id] workspace-default)]
        (assoc state
               :workspace-local local
               :workspace-data data
               :workspace-page page)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter #(or (ptk/type? ::finalize %)
                                   (ptk/type? ::initialize-page %))
                              stream)]
        (rx/concat
         (->> stream
              (rx/filter #(satisfies? IBatchedChange %))
              (rx/debounce 200)
              (rx/map (constantly diff-and-commit-changes))
              (rx/take-until stoper))
         #_(rx/of diff-and-commit-changes))))))

(defn finalize
  [file-id page-id]
  (us/verify ::us/uuid file-id)
  (us/verify ::us/uuid page-id)
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      ;; (prn "finalize-page" page-id)
      (let [local (:workspace-local state)]
        (assoc-in state [:workspace-cache page-id] local)))))

(defn- generate-operations
  [ma mb]
  (let [ma-keys (set (keys ma))
        mb-keys (set (keys mb))
        added (set/difference mb-keys ma-keys)
        removed (set/difference ma-keys mb-keys)
        both (set/intersection ma-keys mb-keys)]
    (d/concat
     (mapv #(array-map :type :set :attr % :val (get mb %)) added)
     (mapv #(array-map :type :set :attr % :val nil) removed)
     (loop [k (first both)
            r (rest both)
            rs []]
       (if k
         (let [vma (get ma k)
               vmb (get mb k)]
           (if (= vma vmb)
             (recur (first r) (rest r) rs)
             (recur (first r) (rest r) (conj rs {:type :set
                                                 :attr k
                                                 :val vmb}))))
         rs)))))

(defn- generate-changes
  [prev curr]
  (letfn [(impl-diff [res id]
            (let [prev-obj (get-in prev [:objects id])
                  curr-obj (get-in curr [:objects id])
                  ops (generate-operations (dissoc prev-obj :shapes :frame-id)
                                           (dissoc curr-obj :shapes :frame-id))]
              (if (empty? ops)
                res
                (conj res {:type :mod-obj
                           :operations ops
                           :id id}))))]
    (reduce impl-diff [] (set/union (set (keys (:objects prev)))
                                    (set (keys (:objects curr)))))))

(def diff-and-commit-changes
  (ptk/reify ::diff-and-commit-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace-page :id])
            curr (get-in state [:workspace-data])
            prev (get-in state [:pages-data pid])

            changes (generate-changes prev curr)
            undo-changes (generate-changes curr prev)]
        ;; (prn "diff-and-commit-changes1" changes)
        ;; (prn "diff-and-commit-changes2" undo-changes)
        (when-not (empty? changes)
          (rx/of (commit-changes changes undo-changes)))))))

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

(s/def ::file ::dp/file)
(s/def ::page
  (s/keys :req-un [::id
                   ::name
                   ::file-id
                   ::version
                   ::revn
                   ::created-at
                   ::modified-at
                   ::ordering
                   ::data]))

;; --- Fetch Workspace Users

(declare users-fetched)
(declare file-fetched)

(defn fetch-file-with-users
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::fetch-file-with-users
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :file-with-users {:id id})
           (rx/merge-map (fn [result]
                           (rx/of (file-fetched (dissoc result :users))
                                  (users-fetched (:users result)))))))))
(defn fetch-file
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::fetch-file
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :file {:id id})
           (rx/map file-fetched)))))

(defn file-fetched
  [{:keys [id] :as file}]
  (us/verify ::file file)
  (ptk/reify ::file-fetched
    ptk/UpdateEvent
    (update [_ state]
      (update state :files assoc id file))))

(defn users-fetched
  [users]
  (ptk/reify ::users-fetched
    ptk/UpdateEvent
    (update [_ state]
      (reduce (fn [state user]
                (update-in state [:workspace-users :by-id (:id user)] merge user))
              state
              users))))


;; --- Fetch Pages

(declare pages-fetched)
(declare unpack-page)

(defn fetch-pages
  [file-id]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::fetch-pages
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :pages {:file-id file-id})
           (rx/map pages-fetched)))))

(defn fetch-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::fetch-pages
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :page {:id page-id})
           (rx/map #(pages-fetched [%]))))))

(defn pages-fetched
  [pages]
  (us/verify (s/every ::page) pages)
  (ptk/reify ::pages-fetched
    IDeref
    (-deref [_] pages)

    ptk/UpdateEvent
    (update [_ state]
      (reduce unpack-page state pages))))

;; --- Page Crud

(declare page-created)

(def create-empty-page
  (ptk/reify ::create-empty-page
    ptk/WatchEvent
    (watch [this state stream]
      (let [file-id (get-in state [:workspace-page :file-id])
            name (str "Page " (gensym "p"))
            ordering (count (get-in state [:files file-id :pages]))
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
          (unpack-page page)))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (fetch-file file-id)))))

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [id name]
  (us/verify ::us/uuid id)
  (us/verify string? name)
  (ptk/reify ::rename-page
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspac-page :id])
            state (assoc-in state [:pages id :name] name)]
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
                               (rx/of (go-to-file (:file-id page)))
                               (rx/empty))))))))))


;; --- Fetch Workspace Images

(declare images-fetched)

(defn fetch-images
  [file-id]
  (ptk/reify ::fetch-images
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :file-images {:file-id file-id})
           (rx/map images-fetched)))))

(defn images-fetched
  [images]
  (ptk/reify ::images-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [images (d/index-by :id images)]
        (assoc state :workspace-images images)))))


;; --- Upload Image

(declare image-uploaded)
(def allowed-file-types #{"image/jpeg" "image/png"})

(defn upload-image
  ([file] (upload-image file identity))
  ([file on-uploaded]
   (us/verify fn? on-uploaded)
   (ptk/reify ::upload-image
     ptk/UpdateEvent
     (update [_ state]
       (assoc-in state [:workspace-local :uploading] true))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [allowed-file? #(contains? allowed-file-types (.-type %))
             finalize-upload #(assoc-in % [:workspace-local :uploading] false)
             file-id (get-in state [:workspace-page :file-id])

             on-success #(do (st/emit! finalize-upload)
                             (on-uploaded %))
             on-error #(do (st/emit! finalize-upload)
                           (rx/throw %))

             prepare
             (fn [file]
               {:name (.-name file)
                :file-id file-id
                :content file})]
         (->> (rx/of file)
              (rx/filter allowed-file?)
              (rx/map prepare)
              (rx/mapcat #(rp/mutation! :upload-file-image %))
              (rx/do on-success)
              (rx/map image-uploaded)
              (rx/catch on-error)))))))


(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::width ::us/number)
(s/def ::height ::us/number)
(s/def ::mtype ::us/string)
(s/def ::uri ::us/string)
(s/def ::thumb-uri ::us/string)

(s/def ::image
  (s/keys :req-un [::id
                   ::name
                   ::width
                   ::height
                   ::uri
                   ::thumb-uri]))

(defn image-uploaded
  [item]
  (us/verify ::image item)
  (ptk/reify ::image-created
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-images assoc (:id item) item))))


;; --- Helpers

(defn unpack-page
  [state {:keys [id data] :as page}]
  (-> state
      (update :pages assoc id (dissoc page :data))
      (update :pages-data assoc id data)))

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (if-let [file-id (get-in state [:pages id :file-id])]
    (-> state
        (update-in [:files file-id :pages] #(filterv (partial not= id) %))
        (update-in [:workspace-file :pages] #(filterv (partial not= id) %))
        (update :pages dissoc id)
        (update :pages-data dissoc id))
    state))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace State Manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Toggle layout flag

(defn toggle-layout-flag
  [flag]
  (us/verify keyword? flag)
  (ptk/reify ::toggle-layout-flag
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-layout
              (fn [flags]
                (if (contains? flags flag)
                  (disj flags flag)
                  (conj flags flag)))))))

;; --- Workspace Flags

(defn activate-flag
   [flag]
  (us/verify keyword? flag)
  (ptk/reify ::activate-flag
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :flags]
                 (fn [flags]
                   (if (contains? flags flag)
                     flags
                     (conj flags flag)))))))

(defn deactivate-flag
  [flag]
  (us/verify keyword? flag)
  (ptk/reify ::deactivate-flag
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :flags] disj flag))))

(defn toggle-flag
  [flag]
  (us/verify keyword? flag)
  (ptk/reify ::toggle-flag
    ptk/WatchEvent
    (watch [_ state stream]
      (let [flags (get-in state [:workspace-local :flags])]
        (if (contains? flags flag)
          (rx/of (deactivate-flag flag))
          (rx/of (activate-flag flag)))))))

;; --- Tooltip

(defn assign-cursor-tooltip
  [content]
  (ptk/reify ::assign-cursor-tooltip
    ptk/UpdateEvent
    (update [_ state]
      (if (string? content)
        (assoc-in state [:workspace-local :tooltip] content)
        (assoc-in state [:workspace-local :tooltip] nil)))))

;; --- Workspace Ruler

(defrecord ActivateRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of #_(set-tooltip "Drag to use the ruler")
           (activate-flag :ruler))))

(defn activate-ruler
  []
  (ActivateRuler.))

(defrecord DeactivateRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of #_(set-tooltip nil)
           (deactivate-flag :ruler))))

(defn deactivate-ruler
  []
  (DeactivateRuler.))

(defrecord ToggleRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [flags (get-in state [:workspace :flags])]
      (if (contains? flags :ruler)
        (rx/of (deactivate-ruler))
        (rx/of (activate-ruler))))))

(defn toggle-ruler
  []
  (ToggleRuler.))

;; --- Icons Toolbox

(defrecord SelectIconsToolboxCollection [id]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :icons-toolbox] id))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (udi/fetch-icons id))))

(defn select-icons-toolbox-collection
  [id]
  {:pre [(or (nil? id) (uuid? id))]}
  (SelectIconsToolboxCollection. id))

(defrecord InitializeIconsToolbox []
  ptk/WatchEvent
  (watch [_ state stream]
    (letfn [(get-first-with-icons [colls]
              (->> (sort-by :name colls)
                   (filter #(> (:num-icons %) 0))
                   (first)
                   (:id)))
            (on-fetched [event]
              (let [coll (get-first-with-icons @event)]
                (select-icons-toolbox-collection coll)))]
      (rx/merge
       (rx/of (udi/fetch-collections)
              (udi/fetch-icons nil))

       ;; Only perform the autoselection if it is not
       ;; previously already selected by the user.
       ;; TODO
       #_(when-not (contains? (:workspace state) :icons-toolbox)
         (->> stream
              (rx/filter udi/collections-fetched?)
              (rx/take 1)
              (rx/map on-fetched)))))))

(defn initialize-icons-toolbox
  []
  (InitializeIconsToolbox.))

;; --- Zoom Management

(def increase-zoom
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [increase #(nth c/zoom-levels
                           (+ (d/index-of c/zoom-levels %) 1)
                           (last c/zoom-levels))]
        (update-in state [:workspace-local :zoom] (fnil increase 1))))))

(def decrease-zoom
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [decrease #(nth c/zoom-levels
                           (- (d/index-of c/zoom-levels %) 1)
                           (first c/zoom-levels))]
        (update-in state [:workspace-local :zoom] (fnil decrease 1))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :zoom] 1))))

;; --- Grid Alignment

;; (defn initialize-alignment
;;   [id]
;;   (us/verify ::us/uuid id)
;;   (ptk/reify ::initialize-alignment
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (let [metadata (get-in state [:workspace-page :metadata])
;;             params {:width c/viewport-width
;;                     :height c/viewport-height
;;                     :x-axis (:grid-x-axis metadata c/grid-x-axis)
;;                     :y-axis (:grid-y-axis metadata c/grid-y-axis)}]
;;         (rx/concat
;;          (rx/of (deactivate-flag :grid-indexed))
;;          (->> (uwrk/initialize-alignment params)
;;               (rx/map #(activate-flag :grid-indexed))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shapes events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Add shape to Workspace

(defn impl-retrieve-used-names
  "Returns a set of already used names by shapes
  in the current workspace page."
  [state]
  (let [data (:workspace-data state)]
    (into #{} (map :name) (vals (:objects data)))))

(defn impl-generate-unique-name
  "A unique name generator based on the current workspace page."
  [state basename]
  (let [used (impl-retrieve-used-names state)]
    (loop [counter 1]
      (let [candidate (str basename "-" counter)]
        (if (contains? used candidate)
          (recur (inc counter))
          candidate)))))

(defn impl-assoc-shape
  [state {:keys [id frame-id] :as data}]
  (let [name (impl-generate-unique-name state (:name data))
        shape (assoc data :name name)]
    (-> state
        (update-in [:workspace-data :objects frame-id :shapes] conj id)
        (update-in [:workspace-data :objects] assoc id shape))))

(declare select-shape)

(def shape-default-attrs
  {:stroke-color "#000000"
   :stroke-opacity 1
   :fill-color "#000000"
   :fill-opacity 1})

(defn- calculate-frame-overlap
  [data shape]
  (let [objects (:objects data)
        rshp (geom/shape->rect-shape shape)

        xfmt (comp
              (filter #(= :frame (:type %)))
              (filter #(not= (:id shape) (:id %)))
              (filter #(not= uuid/zero (:id %)))
              (filter #(geom/overlaps? % rshp)))

        frame (->> (vals objects)
                   (sequence xfmt)
                   (first))]

    (or (:id frame) uuid/zero)))

(defn add-shape
  [attrs]
  (us/verify ::shape-attrs attrs)
  (let [id (uuid/next)]
    (ptk/reify ::add-shape
      ptk/UpdateEvent
      (update [_ state]
        (let [data (:workspace-data state)
              shape (-> (geom/setup-proportions attrs)
                        (assoc :id id))
              frame-id (calculate-frame-overlap data shape)
              shape (merge shape-default-attrs shape {:frame-id frame-id})]
          (impl-assoc-shape state shape)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [obj (get-in state [:workspace-data :objects id])]
          (rx/of (commit-changes [{:type :add-obj
                                   :id id
                                   :frame-id (:frame-id obj)
                                   :obj obj}]
                                 [{:type :del-obj
                                   :id id}])))))))

(def frame-default-attrs
  {:stroke-color "#000000"
   :stroke-opacity 1
   :frame-id uuid/zero
   :fill-color "#ffffff"
   :shapes []
   :fill-opacity 1})

(defn add-frame
  [data]
  (us/verify ::shape-attrs data)
  (let [id (uuid/next)]
    (ptk/reify ::add-frame
      ptk/UpdateEvent
      (update [_ state]
        (let [shape (-> (geom/setup-proportions data)
                        (assoc :id id))
              shape (merge frame-default-attrs shape)]
          (impl-assoc-shape state shape)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [obj (get-in state [:workspace-data :objects id])]
          (rx/of (commit-changes [{:type :add-obj
                                   :id id
                                   :frame-id (:frame-id obj)
                                   :obj obj}]
                                 [{:type :del-obj
                                   :id id}])))))))


;; --- Duplicate Selected

(defn duplicate-shapes
  [shapes]
  (ptk/reify ::duplicate-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (prn "duplicate-shapes" shapes)
      (let [objects (get-in state [:workspace-data :objects])
            rchanges (mapv (fn [id]
                             (let [obj (get objects id)
                                   obj (assoc obj :id (uuid/next))]
                               {:type :add-obj
                                :id (:id obj)
                                :frame-id (:frame-id obj)
                                :obj obj
                                :session-id (:session-id state)}))
                           shapes)
            uchanges (mapv (fn [rch]
                             {:type :del-obj
                              :id (:id rch)
                              :session-id (:session-id state)})
                           rchanges)]
        (rx/of (commit-changes rchanges uchanges {:commit-local? true}))))))

(defn duplicate-frame
  [{:keys [id] :as frame} prev-id]
  (ptk/reify ::duplicate-frame
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-data :objects id] frame)
          (update-in [:workspace-data :frame] (fnil conj []) id)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [shapes (->> (vals (get-in state [:workspace-data :objects]))
                        (filter #(= (:frame %) prev-id))
                        (map #(assoc % :id (uuid/next)))
                        (map #(assoc % :frame id)))

            rchange {:type :add-frame
                     :id id
                     :shape frame
                     :session-id (:session-id state)}
            uchange {:type :del-frame
                     :id id
                     :session-id (:session-id state)}]
        (rx/of (duplicate-shapes shapes)
               (commit-changes [rchange] [uchange]))))))


(def duplicate-selected
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])
            dup #(-> (get-in state [:workspace-data :objects %])
                     (assoc :id (uuid/next)))
            shapes (map dup selected)
            shape? #(not= (:type %) :frame)]
        (cond
          (and (= (count shapes) 1)
               (= (:type (first shapes)) :frame))
          (rx/of (duplicate-frame (first shapes) (first selected)))

          (and (pos? (count shapes))
               (every? shape? shapes))
          ;; (rx/of (duplicate-shapes shapes))
          (rx/of (duplicate-shapes selected))

          :else
          (rx/empty))))))



;; --- Toggle shape's selection status (selected or deselected)

(defn select-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::select-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :selected]
                 (fn [selected]
                   (if (contains? selected id)
                     (disj selected id)
                     (conj selected id)))))))

(def deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user."
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local #(-> %
                                          (assoc :selected #{})
                                          (dissoc :selected-frame))))))


;; --- Select Shapes (By selrect)

(defn- impl-try-match-shape
  [selrect acc {:keys [type id] :as shape}]
  (cond
    (geom/contained-in? shape selrect)
    (conj acc id)

    (geom/overlaps? shape selrect)
    (conj acc id)

    :else
    acc))

(defn impl-match-by-selrect
  [state selrect]
  (let [data (:workspace-data state)
        match (partial impl-try-match-shape selrect)
        xf (comp (remove :hidden)
                 (remove :blocked)
                 (remove #(= :frame (:type %)))
                 (remove #(= uuid/zero (:id %)))
                 (map geom/shape->rect-shape)
                 (map geom/resolve-rotation)
                 (map geom/shape->rect-shape))]
    (transduce xf match #{} (vals (:objects data)))))

(def select-shapes-by-current-selrect
  (ptk/reify ::select-shapes-by-current-selrect
    ptk/UpdateEvent
    (update [_ state]
      (let [{:keys [selrect id]} (:workspace-local state)]
        (->> (impl-match-by-selrect state selrect)
             (assoc-in state [:workspace-local :selected]))))))

;; --- Update Shape Attrs

(defn update-shape
  [id attrs]
  (us/verify ::us/uuid id)
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-shape
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-data :objects id] merge attrs))))

;; --- Update Page Options

(defn update-options
  [opts]
  (us/verify ::cp/options opts)
  (ptk/reify ::update-options
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-data :options] merge opts))))

;; --- Update Selected Shapes attrs

(defn update-selected-shapes
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-selected-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/from (map #(update-shape % attrs) selected))))))

;; --- Shape Movement (using keyboard shorcuts)

(declare initial-selection-align)

(defn- get-displacement-with-grid
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction options]
  (let [grid-x (:grid-x options 10)
        grid-y (:grid-y options 10)
        x-mod (mod (:x shape) grid-x)
        y-mod (mod (:y shape) grid-y)]
    (case direction
      :up (gpt/point 0 (- (if (zero? y-mod) grid-y y-mod)))
      :down (gpt/point 0 (- grid-y y-mod))
      :left (gpt/point (- (if (zero? x-mod) grid-x x-mod)) 0)
      :right (gpt/point (- grid-x x-mod) 0))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(s/def ::direction #{:up :down :right :left})

(declare apply-displacement-in-bulk)
(declare materialize-displacement-in-bulk)

(defn move-selected
  [direction align?]
  (us/verify ::direction direction)
  (us/verify boolean? align?)

  (ptk/reify ::move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])
            options (get-in state [:workspace-data :options])
            shapes (map #(get-in state [:workspace-data :objects %]) selected)
            shape (geom/shapes->rect-shape shapes)
            displacement (if align?
                           (get-displacement-with-grid shape direction options)
                           (get-displacement shape direction))]
        (rx/of (apply-displacement-in-bulk selected displacement)
               (materialize-displacement-in-bulk selected))))))

;; --- Delete Selected

(defn- impl-dissoc-shape
  "Given a shape id, removes it from the state."
  [state id]
  (let [data (:workspace-data state)
        fid  (get-in data [:rmap id])
        shp  (get-in data [:frames fid :objects id])
        data (-> data
                 (update-in [:frames fid :shapes] (fn [s] (filterv #(not= % id) s)))
                 (update-in [:frames fid :objects] dissoc id))]
    (assoc data :workspace-data data)))

(defn- impl-purge-shapes
  [ids]
  (ptk/reify ::impl-purge-shapes
    ptk/UpdateEvent
    (update [_ state]
      (reduce impl-dissoc-shape state ids))))

(defn- delete-shapes
  [ids]
  (us/assert ::set-of-uuid ids)
  (ptk/reify ::delete-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [objects (get-in state [:workspace-data :objects])
            session-id (:session-id state)

            ids (seq ids)

            rchanges (mapv #(array-map :type :del-obj :id %) ids)
            uchanges (mapv (fn [id]
                             (let [obj (get objects id)
                                   frm (get objects (:frame-id obj))
                                   idx (d/index-of (:shapes frm) id)]
                               {:type :add-obj
                                :id id
                                :frame-id (:id frm)
                                :index idx
                                :obj obj}))
                           (reverse ids))]
        (rx/of (commit-changes rchanges uchanges {:commit-local? true}))))))

(defn- delete-frame
  [id]
  (ptk/reify ::delete-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [objects (get-in state [:workspace-data :objects])
            obj (get objects id)
            ids (d/concat #{} (:shapes obj) [(:id obj)])]
        (rx/of (delete-shapes ids))))))

(def delete-selected
  "Deselect all and remove all selected shapes."
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [lookup   #(get-in state [:workspace-data :objects %])
            selected (get-in state [:workspace-local :selected])

            shapes (map lookup selected)
            shape? #(not= (:type %) :frame)]

        (cond
          (and (= (count shapes) 1)
               (= (:type (first shapes)) :frame))
          (rx/of (delete-frame (first selected)))

          (and (pos? (count shapes))
               (every? shape? shapes))
          (rx/of (delete-shapes selected))

          :else
          (rx/empty))))))

;; --- Rename Shape

(defn rename-shape
  [id name]
  (us/verify ::us/uuid id)
  (us/verify string? name)
  (ptk/reify ::rename-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape (get-in state [:workspace-data :objects id])
            session-id (:session-id state)
            change  {:type :mod-shape
                     :id id
                     :session-id session-id
                     :operations [[:set :name name]]}
            uchange {:type :mod-shape
                     :id id
                     :session-id session-id
                     :operations [[:set :name (:name shape)]]}]
        (rx/of (commit-changes [change] [uchange]))))))

;; --- Shape Vertical Ordering

(declare impl-order-shape)

(defn order-selected-shapes
  [loc]
  (us/verify ::direction loc)
  (ptk/reify ::move-selected-layer
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [id (first (get-in state [:workspace-local :selected]))
            type (get-in state [:workspace-data :objects id :type])]
        ;; NOTE: multiple selection ordering not supported
        (if (and id (not= type :frame))
          (impl-order-shape state id loc)
          state)))))

(defn impl-order-shape
  [state sid opt]
  (let [shapes (get-in state [:workspace-data :shapes])
        index (case opt
                :top 0
                :down (min (- (count shapes) 1) (inc (d/index-of shapes sid)))
                :up (max 0 (- (d/index-of shapes sid) 1))
                :bottom (- (count shapes) 1))]
    (update-in state [:workspace-data :shapes]
               (fn [items]
                 (let [[fst snd] (->> (remove #(= % sid) items)
                                      (split-at index))]
                   (into [] (concat fst [sid] snd)))))))

;; --- Change Shape Order (D&D Ordering)

(defn shape-order-change
  [id index]
  (us/verify ::us/uuid id)
  (us/verify number? index)
  (ptk/reify ::change-shape-order
    ptk/UpdateEvent
    (update [_ state]
      (let [obj (get-in state [:workspace-data :objects id])
            frm (get-in state [:workspace-data :objects (:frame-id obj)])
            shp (remove #(= % id) (:shapes frm))
            [b a] (split-at index shp)
            shp (d/concat [] b [id] a)]
        (assoc-in state [:workspace-data :objects (:id frm) :shapes] shp)))))

(defn commit-shape-order-change
  [id]
  (ptk/reify ::commit-shape-order-change
    ptk/WatchEvent
    (watch [_ state stream]

      (let [obj (get-in state [:workspace-data :objects id])
            pid (get-in state [:workspace-page :id])

            cfrm (get-in state [:workspace-data :objects (:frame-id obj)])
            pfrm (get-in state [:pages-data pid :objects (:frame-id obj)])

            cindex (d/index-of (:shapes cfrm) id)
            pindex (d/index-of (:shapes pfrm) id)

            session-id (:session-id state)

            rchange {:type :mod-obj
                     :session-id session-id
                     :id (:id cfrm)
                     :operations [{:type :order :id id :index cindex}]}
            uchange {:type :mod-obj
                     :session-id session-id
                     :id (:id cfrm)
                     :operations [{:type :order :id id :index pindex}]}]
        (prn "commit-shape-order-change3" rchange)
        (rx/of (commit-changes [rchange] [uchange]))))))

;; --- Change Frame Order (D&D Ordering)

(defn change-frame-order
  [{:keys [id index] :as params}]
  (us/verify ::us/uuid id)
  (us/verify ::us/number index)
  #_(ptk/reify ::change-frame-order
    ptk/UpdateEvent
    (update [_ state]
      (let [shapes (get-in state [:workspace-data :frame])
            shapes (into [] (remove #(= % id)) shapes)
            [before after] (split-at index shapes)
            shapes (vec (concat before [id] after))]
        (assoc-in state [:workspace-data :frame] shapes)))))

;; --- Shape / Selection Alignment

(defn initial-selection-align
  "Align the selection of shapes."
  [ids]
  (us/verify ::set-of-uuid ids)
  (ptk/reify ::initialize-shapes-align-in-bulk
    ptk/WatchEvent
    (watch [_ state stream]
      #_(let [shapes-by-id (get-in state [:workspace-data :objects])
            shapes (mapv #(get shapes-by-id %) ids)
            sshape (geom/shapes->rect-shape shapes)
            point (gpt/point (:x1 sshape)
                             (:y1 sshape))]
        (->> (uwrk/align-point point)
             (rx/map (fn [{:keys [x y] :as pt}]
                       (apply-displacement-in-bulk ids (gpt/subtract pt point)))))))))

;; --- Temportal displacement for Shape / Selection

(defn- rehash-shape-frame-relationship
  [ids]
  (letfn [(impl-diff [state]
            (loop [id  (first ids)
                   ids (rest ids)
                   rch []
                   uch []]
              (if (nil? id)
                [rch uch]
                (let [dta (:workspace-data state)
                      obj (get-in dta [:objects id])
                      fid (calculate-frame-overlap dta obj)]
                  (if (not= fid (:frame-id obj))
                    (recur (first ids)
                           (rest ids)
                           (conj rch {:type :mov-obj
                                      :id id
                                      :frame-id fid})
                           (conj uch {:type :mov-obj
                                      :id id
                                      :frame-id (:frame-id obj)}))
                    (recur (first ids)
                           (rest ids)
                           rch
                           uch))))))]
    (ptk/reify ::rehash-shape-frame-relationship
      ptk/WatchEvent
      (watch [_ state stream]
        (let [[rch uch] (impl-diff state)]
          (when-not (empty? rch)
            (rx/of (commit-changes rch uch {:commit-local? true}))))))))

(defn assoc-resize-modifier-in-bulk
  [ids xfmt]
  (us/verify ::set-of-uuid ids)
  (us/verify gmt/matrix? xfmt)
  (ptk/reify ::assoc-resize-modifier-in-bulk
    ptk/UpdateEvent
    (update [_ state]
      (reduce #(assoc-in %1 [:workspace-data :objects %2 :resize-modifier] xfmt) state ids))))

(defn materialize-resize-modifier-in-bulk
  [ids]
  (letfn [(process-shape [state id]
            (update-in state [:workspace-data :objects id]
                       (fn [shape]
                         (let [mfr (:resize-modifier shape (gmt/matrix))]
                           (-> (dissoc shape :resize-modifier)
                               (geom/transform mfr))))))]
    (ptk/reify ::materialize-resize-modifier-in-bulk
      ptk/UpdateEvent
      (update [_ state]
        (reduce process-shape state ids))

      ptk/WatchEvent
      (watch [_ state stream]
        (rx/of diff-and-commit-changes
               (rehash-shape-frame-relationship ids))))))


(defn apply-displacement-in-bulk
  "Apply the same displacement delta to all shapes identified by the
  set if ids."
  [ids delta]
  (us/verify ::set-of-uuid ids)
  (us/verify gpt/point? delta)
  (letfn [(process-shape [state id]
            (let [objects (get-in state [:workspace-data :objects])
                  shape   (get objects id)
                  prev    (:displacement-modifier shape (gmt/matrix))
                  curr    (gmt/translate prev delta)]
              (->> (assoc shape :displacement-modifier curr)
                   (assoc-in state [:workspace-data :objects id]))))]
    (ptk/reify ::apply-displacement-in-bulk
      ptk/UpdateEvent
      (update [_ state]
        (reduce process-shape state ids)))))

(defn materialize-displacement-in-bulk
  [ids]
  (letfn [(process-shape [state id]
            (update-in state [:workspace-data :objects id]
                       (fn [shape]
                         (let [mtx (:displacement-modifier shape (gmt/matrix))]
                           (-> (dissoc shape :displacement-modifier)
                               (geom/transform mtx))))))]

    (ptk/reify ::materialize-displacement-in-bulk
      ptk/UpdateEvent
      (update [_ state]
        (reduce process-shape state ids))

      ptk/WatchEvent
      (watch [_ state stream]
        (rx/of diff-and-commit-changes
               (rehash-shape-frame-relationship ids))))))


(defn apply-frame-displacement
  "Apply the same displacement delta to all shapes identified by the
  set if ids."
  [id delta]
  (us/verify ::us/uuid id)
  (us/verify gpt/point? delta)
  (ptk/reify ::apply-frame-displacement
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (get-in state [:workspace-data :objects id])
            prev-xfmt (:displacement-modifier shape (gmt/matrix))
            xfmt (gmt/translate prev-xfmt delta)]
        (->> (assoc shape :displacement-modifier xfmt)
             (assoc-in state [:workspace-data :objects id]))))))

(defn materialize-frame-displacement
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::materialize-frame-displacement
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      ;; (prn "materialize-frame-displacement")
      (let [objects (get-in state [:workspace-data :objects])
            frame   (get objects id)
            xmt     (or (:displacement-modifier frame) (gmt/matrix))

            frame   (-> frame
                        (dissoc :displacement-modifier)
                        (geom/transform xmt))

            shapes  (->> (:shapes frame)
                         (map #(get objects %))
                         (map #(geom/transform % xmt))
                         (d/index-by :id))

            shapes (assoc shapes (:id frame) frame)]

        (update-in state [:workspace-data :objects] merge shapes)))))


(defn commit-changes
  ([changes undo-changes] (commit-changes changes undo-changes {}))
  ([changes undo-changes {:keys [save-undo?
                                 commit-local?]
                          :or {save-undo? true
                               commit-local? false}
                          :as opts}]
   (us/verify ::cp/changes changes)
   (us/verify ::cp/changes undo-changes)

   (ptk/reify ::commit-changes
     ptk/UpdateEvent
     (update [_ state]
       (let [pid (get-in state [:workspace-page :id])
             state (update-in state [:pages-data pid] cp/process-changes changes)]
         (cond-> state
           commit-local? (update-in [:workspace-data] cp/process-changes changes))))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [page (:workspace-page state)
             uidx (get-in state [:workspace-local :undo-index] ::not-found)
             params {:id (:id page)
                     :revn (:revn page)
                     :changes (vec changes)}]

         (rx/concat
          (when (and save-undo? (not= uidx ::not-found))
            (rx/of (reset-undo uidx)))

          (when save-undo?
            (let [entry {:undo-changes undo-changes
                         :redo-changes changes}]
              (rx/of (append-undo entry))))

          (->> (rp/mutation :update-page params)
               (rx/map shapes-changes-commited))))))))


;; (defn- check-page-integrity
;;   [data]
;;   (let [items (d/concat (:shapes data)
;;                         (:frame data))]
;;     (loop [id (first items)
;;            ids (rest items)]
;;       (let [content (get-in data [:objects id])]
;;         (cond
;;           (nil? id)
;;           nil
;;           (nil? content)
;;           (ex/raise :type :validation
;;                     :code :shape-integrity
;;                     :context {:id id})
;;
;;           :else
;;           (recur (first ids) (rest ids)))))))

(s/def ::shapes-changes-commited
  (s/keys :req-un [::page-id ::revn ::cp/changes]))

(defn shapes-changes-commited
  [{:keys [page-id revn changes] :as params}]
  (us/verify ::shapes-changes-commited params)
  (ptk/reify ::shapes-changes-commited
    ptk/UpdateEvent
    (update [_ state]
      ;; (prn "shapes-changes-commited$update"
      ;;      (get-in state [:workspace-page :id])
      ;;      page-id)
      (-> state
          (assoc-in [:workspace-page :revn] revn)
          (assoc-in [:pages page-id :revn] revn)
          (update-in [:pages-data page-id] cp/process-changes changes)
          (update :workspace-data cp/process-changes changes)))

    ptk/EffectEvent
    (effect [_ state stream]
      #_(when *assert*
        (check-page-integrity (:workspace-data state))))))

;; --- Start shape "edition mode"

(declare clear-edition-mode)

(defn start-edition-mode
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::start-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :edition] id))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter #(= % :interrupt))
           (rx/take 1)
           (rx/map (constantly clear-edition-mode))))))

(def clear-edition-mode
  (ptk/reify ::clear-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :edition))))

;; --- Select for Drawing

(def clear-drawing
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :drawing-tool :drawing))))

(defn select-for-drawing
  ([tool] (select-for-drawing tool nil))
  ([tool data]
   (ptk/reify ::select-for-drawing
     ptk/UpdateEvent
     (update [_ state]
       (update state :workspace-local assoc :drawing-tool tool :drawing data))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [cancel-event? (fn [event]
                             (interrupt? event))
             stoper (rx/filter (ptk/type? ::clear-drawing) stream)]
         (->> (rx/filter cancel-event? stream)
              (rx/take 1)
              (rx/map (constantly clear-drawing))
              (rx/take-until stoper)))))))

;; --- Update Dimensions

(defn update-rect-dimensions
  [id attr value]
  (us/verify ::us/uuid id)
  (us/verify #{:width :height} attr)
  (us/verify ::us/number value)
  (ptk/reify ::update-rect-dimensions
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-data :objects id] geom/resize-rect attr value))))

(defn update-circle-dimensions
  [id attr value]
  (us/verify ::us/uuid id)
  (us/verify #{::rx ::ry} attr)
  (us/verify ::us/number value)
  (ptk/reify ::update-rect-dimensions
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-data :objects id] geom/resize-rect attr value))))

;; --- Shape Proportions

(defn toggle-shape-proportion-lock
  [id]
  (ptk/reify ::toggle-shape-proportion-lock
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (get-in state [:workspace-data :objects id])]
        (if (:proportion-lock shape)
          (assoc-in state [:workspace-data :objects id :proportion-lock] false)
          (->> (geom/assign-proportions (assoc shape :proportion-lock true))
               (assoc-in state [:workspace-data :objects id])))))))

;; --- Update Shape Position

(s/def ::x number?)
(s/def ::y number?)
(s/def ::position
  (s/keys :opt-un [::x ::y]))

(defn update-position
  [id position]
  (us/verify ::us/uuid id)
  (us/verify ::position position)
  (ptk/reify ::update-position
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-data :objects id]
                 geom/absolute-move position))))

;; --- Path Modifications

(defn update-path
  "Update a concrete point in the path shape."
  [id index delta]
  (us/verify ::us/uuid id)
  (us/verify ::us/integer index)
  (us/verify gpt/point? delta)
  (ptk/reify ::update-path
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-data :objects id :segments index] gpt/add delta))))

;; --- Initial Path Point Alignment

;; ;; TODO: revisit on alignemt refactor
;; (deftype InitialPathPointAlign [id index]
;;   ptk/WatchEvent
;;   (watch [_ state s]
;;     (let [shape (get-in state [:workspace-data :objects id])
;;           point (get-in shape [:segments index])]
;;       (->> (uwrk/align-point point)
;;            (rx/map #(update-path id index %))))))

;; (defn initial-path-point-align
;;   "Event responsible of align a specified point of the
;;   shape by `index` with the grid."
;;   [id index]
;;   {:pre [(uuid? id)
;;          (number? index)
;;          (not (neg? index))]}
;;   (InitialPathPointAlign. id index))

;; --- Shape Visibility

(declare impl-update-shape-hidden)

(defn hide-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::hide-shape
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (impl-update-shape-hidden state id true))))

(defn show-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::show-shape
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (impl-update-shape-hidden state id false))))

(defn hide-frame
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::hide-shape
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [hide #(impl-update-shape-hidden %1 %2 true)
            ids (->> (vals (get-in state [:workspace-data :objects]))
                     (filter #(= (:frame %) id))
                     (map :id))]
        (reduce hide state (cons id ids))))))

(defn show-frame
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::hide-shape
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [show #(impl-update-shape-hidden %1 %2 false)
            ids (->> (vals (get-in state [:workspace-data :objects]))
                     (filter #(= (:frame %) id))
                     (map :id))]
        (reduce show state (cons id ids))))))

(defn- impl-update-shape-hidden
  [state id hidden?]
  (assoc-in state [:workspace-data :objects id :hidden] hidden?))

;; --- Shape Blocking

(declare impl-update-shape-blocked)

(defn block-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::hide-shape
    ptk/UpdateEvent
    (update [_ state]
      (impl-update-shape-blocked state id true))))

(defn unblock-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::hide-shape
    ptk/UpdateEvent
    (update [_ state]
      (impl-update-shape-blocked state id false))))

(defn- impl-update-shape-blocked
  [state id hidden?]
  (let [type  (get-in state [:workspace-data :objects id :type])
        state (update-in state [:workspace-data :objects id] assoc :blocked hidden?)]
    (cond-> state
      (= type :frame)
      (update-in [:workspace-data :objects]
                 (fn [shapes]
                   (reduce-kv (fn [shapes key {:keys [frame] :as val}]
                                (cond-> shapes
                                  (= id frame) (update key assoc :blocked hidden?)))
                              shapes
                              shapes))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frame Interactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn select-frame
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::select-frame
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local assoc :selected-frame id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn navigate-to-project
  [project-id]
  (ptk/reify ::navigate-to-project
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:projects project-id :pages])
            params {:project project-id :page (first page-ids)}]
        (rx/of (rt/nav :workspace/page params))))))

(defn go-to-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::go-to-page
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file-id (get-in state [:workspace-page :file-id])
            path-params {:file-id file-id}
            query-params {:page-id page-id}]
        (rx/of (rt/nav :workspace path-params query-params))))))

(defn go-to-file
  [file-id]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::go-to-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:files file-id :pages])
            path-params {:file-id file-id}
            query-params {:page-id (first page-ids)}]
        (rx/of (rt/nav :workspace path-params query-params))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Changes Reactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Change Page Order (D&D Ordering)

(defn change-page-order
  [{:keys [id index] :as params}]
  {:pre [(uuid? id) (number? index)]}
  (ptk/reify ::change-page-order
    ptk/UpdateEvent
    (update [_ state]
      (let [page (get-in state [:pages id])
            pages (get-in state [:projects (:project-id page) :pages])
            pages (into [] (remove #(= % id)) pages)
            [before after] (split-at index pages)
            pages (vec (concat before [id] after))]
        (assoc-in state [:projects (:project-id page) :pages] pages)))))

