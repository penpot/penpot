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
   [uxbox.main.data.dashboard :as dd]
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
      (let [page-id (::page-id state)]
        (-> state
            (update-in [:workspace-data page-id] cp/process-changes changes)
            (assoc-in [:workspace-local :undo-index] index))))))

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

(def initialize-layout
  (ptk/reify ::initialize-layout
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-layout default-layout))))

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
          (rx/merge
           (rx/of (fetch-file-with-users file-id)
                  (fetch-pages file-id)
                  (fetch-images file-id))
           (->> (rx/zip (rx/filter (ptk/type? ::pages-fetched) stream)
                        (rx/filter (ptk/type? ::file-fetched) stream))
                (rx/take 1)
                (rx/do (fn [_]
                         (uxbox.util.timers/schedule 500 #(reset! st/loader false))))
                (rx/mapcat (fn [_]
                             (rx/of (initialize-file file-id)
                                    (initialize-page page-id))))))

          (rx/merge
           (rx/of (fetch-page page-id))
           (->> stream
                (rx/filter (ptk/type? ::pages-fetched))
                (rx/take 1)
                (rx/merge-map (fn [_]
                                (rx/of (initialize-file file-id)
                                       (initialize-page page-id)))))))))))

(defn- initialize-file
  [file-id]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::initialize-file
    ptk/UpdateEvent
    (update [_ state]
      (let [file (get-in state [:files file-id])]
        (assoc state :workspace-file file)))))

(declare diff-and-commit-changes)
(declare initialize-page-persistence)

(defn initialize-page
  [page-id]
  (ptk/reify ::initialize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [page (get-in state [:pages page-id])
            data (get-in state [:pages-data page-id])
            local (get-in state [:workspace-cache page-id] workspace-default)]
        (-> state
            (assoc ::page-id page-id)   ; mainly used by events
            (assoc :workspace-local local)
            (assoc :workspace-page page)
            (assoc-in [:workspace-data page-id] data))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (initialize-page-persistence page-id)))))

(defn finalize
  [file-id page-id]
  (us/verify ::us/uuid file-id)
  (us/verify ::us/uuid page-id)
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
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

(defn diff-and-commit-changes
  [page-id]
  (ptk/reify ::diff-and-commit-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            curr (get-in state [:workspace-data page-id])
            prev (get-in state [:pages-data page-id])

            changes (generate-changes prev curr)
            undo-changes (generate-changes curr prev)]
        (when-not (empty? changes)
          (rx/of (commit-changes changes undo-changes)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Persistence
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare persist-changes)

(defn initialize-page-persistence
  [page-id]
  (ptk/reify ::initialize-persistence
    ptk/UpdateEvent
    (update [_ state]
      (assoc state ::page-id page-id))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter #(or (ptk/type? ::finalize %)
                                   (ptk/type? ::initialize-page %))
                              stream)
            notifier (->> stream
                          (rx/filter (ptk/type? ::commit-changes))
                          (rx/debounce 2000)
                          (rx/merge stoper))]
        (rx/merge
         (->> stream
              (rx/filter (ptk/type? ::commit-changes))
              (rx/map deref)
              (rx/buffer-until notifier)
              (rx/map vec)
              (rx/filter (complement empty?))
              (rx/map #(persist-changes page-id %))
              (rx/take-until (rx/delay 100 stoper)))
         (->> stream
              (rx/filter #(satisfies? IBatchedChange %))
              (rx/debounce 200)
              (rx/map (fn [_] (diff-and-commit-changes page-id)))
              (rx/take-until stoper)))))))

(defn persist-changes
  [page-id changes]
  (ptk/reify ::persist-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [session-id (:session-id state)
            page (get-in state [:pages page-id])
            changes (->> changes
                         (mapcat identity)
                         (map #(assoc % :session-id session-id))
                         (vec))
            params {:id (:id page)
                    :revn (:revn page)
                    :changes changes}]
        (->> (rp/mutation :update-page params)
             (rx/map shapes-changes-commited))))))

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

;; --- Tooltip

(defn assign-cursor-tooltip
  [content]
  (ptk/reify ::assign-cursor-tooltip
    ptk/UpdateEvent
    (update [_ state]
      (if (string? content)
        (assoc-in state [:workspace-local :tooltip] content)
        (assoc-in state [:workspace-local :tooltip] nil)))))

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

;; --- Selection Rect

(declare select-shapes-by-current-selrect)
(declare deselect-all)

(defn update-selrect
  [selrect]
  (ptk/reify ::update-selrect
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selrect] selrect))))

(def handle-selection
  (letfn [(data->selrect [data]
            (let [start (:start data)
                  stop (:stop data)
                  start-x (min (:x start) (:x stop))
                  start-y (min (:y start) (:y stop))
                  end-x (max (:x start) (:x stop))
                  end-y (max (:y start) (:y stop))]
              {:type :rect
               :x start-x
               :y start-y
               :width (- end-x start-x)
               :height (- end-y start-y)}))]
    (ptk/reify ::handle-selection
      ptk/WatchEvent
      (watch [_ state stream]
        (let [stoper (rx/filter #(or (interrupt? %)
                                     (ms/mouse-up? %))
                                stream)]
          (rx/concat
           (rx/of deselect-all)
           (->> ms/mouse-position
                (rx/scan (fn [data pos]
                           (if data
                             (assoc data :stop pos)
                             {:start pos :stop pos}))
                         nil)
                (rx/map data->selrect)
                (rx/map update-selrect)
                (rx/take-until stoper))
           (rx/of select-shapes-by-current-selrect
                  (update-selrect nil))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shapes events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Add shape to Workspace

(defn impl-retrieve-used-names
  "Returns a set of already used names by shapes
  in the current workspace page."
  [state]
  (let [page-id (::page-id state)
        objects (get-in state [:workspace-page page-id :objects])]
    (into #{} (map :name) (vals objects))))

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
        shape (assoc data :name name)
        page-id (::page-id state)]
    (-> state
        (update-in [:workspace-data page-id :objects frame-id :shapes] conj id)
        (update-in [:workspace-data page-id :objects] assoc id shape))))

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
        (let [page-id  (::page-id state)
              data     (get-in state [:workspace-data page-id])
              shape    (-> (geom/setup-proportions attrs)
                           (assoc :id id))
              frame-id (calculate-frame-overlap data shape)
              shape    (merge shape-default-attrs shape {:frame-id frame-id})]
          (impl-assoc-shape state shape)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [page-id (::page-id state)
              obj (get-in state [:workspace-data page-id :objects id])]
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
        (let [page-id (::page-id state)
              obj (get-in state [:workspace-data page-id :objects id])]
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
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            rchanges (mapv (fn [id]
                             (let [obj (assoc (get objects id)
                                              :id (uuid/next))]
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
  [frame-id]
  (ptk/reify ::duplicate-frame
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])

            frame (get objects frame-id)
            frame-id (uuid/next)

            rchanges (mapv (fn [id]
                             (let [obj (assoc (get objects id)
                                              :id (uuid/next))]
                               {:type :add-obj
                                :id (:id obj)
                                :frame-id frame-id
                                :obj (assoc obj :frame-id frame-id)
                                :session-id (:session-id state)}))
                           (:shapes frame))

            uchanges (mapv (fn [rch]
                             {:type :del-obj
                              :id (:id rch)
                              :session-id (:session-id state)})
                           rchanges)

            shapes (mapv :id rchanges)

            rchange {:type :add-obj
                     :id frame-id
                     :frame-id uuid/zero
                     :obj (assoc frame
                                 :id frame-id
                                 :shapes shapes)

                     :session-id (:session-id state)}

            uchange {:type :del-obj
                     :id frame-id
                     :session-id (:session-id state)}]
        (rx/of (commit-changes (d/concat [rchange] rchanges)
                               (d/concat [uchange] uchanges)
                               {:commit-local? true}))))))


(def duplicate-selected
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            lookup #(get objects %)
            shapes (map lookup selected)
            shape? #(not= (:type %) :frame)]
        (cond
          (and (= (count shapes) 1)
               (= (:type (first shapes)) :frame))
          (rx/of (duplicate-frame (first selected)))

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
  (let [page-id (::page-id state)
        data (get-in state [:workspace-data page-id])
        match (fn [acc {:keys [type id] :as shape}]
                (cond
                  (geom/contained-in? shape selrect)
                  (conj acc id)

                  (geom/overlaps? shape selrect)
                  (conj acc id)

                  :else
                  acc))

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
      (let [pid (::page-id state)]
        (update-in state [:workspace-data pid :objects id] merge attrs)))))

;; --- Update Page Options

(defn update-options
  [opts]
  (us/verify ::cp/options opts)
  (ptk/reify ::update-options
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (::page-id state)]
        (update-in state [:workspace-data pid :options] merge opts)))))

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
(s/def ::loc  #{:up :down :bottom :top})

(declare apply-displacement-in-bulk)
(declare materialize-displacement-in-bulk)

(defn move-selected
  [direction align?]
  (us/verify ::direction direction)
  (us/verify boolean? align?)

  (ptk/reify ::move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (::page-id state)
            selected (get-in state [:workspace-local :selected])
            options (get-in state [:workspace-data pid :options])
            shapes (map #(get-in state [:workspace-data pid :objects %]) selected)
            shape (geom/shapes->rect-shape shapes)
            displacement (if align?
                           (get-displacement-with-grid shape direction options)
                           (get-displacement shape direction))]
        (rx/of (apply-displacement-in-bulk selected displacement)
               (materialize-displacement-in-bulk selected))))))

;; --- Delete Selected

(defn- delete-shapes
  [ids]
  (us/assert (s/coll-of ::us/uuid) ids)
  (ptk/reify ::delete-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            session-id (:session-id state)
            objects (get-in state [:workspace-data page-id :objects])
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
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            obj (get objects id)
            ids (d/concat [] (:shapes obj) [(:id obj)])]
        (rx/of (delete-shapes ids))))))

(def delete-selected
  "Deselect all and remove all selected shapes."
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            lookup   #(get-in state [:workspace-data page-id :objects %])
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
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)]
        (update-in state [:workspace-data page-id :objects id] assoc :name name)))))

;; --- Shape Vertical Ordering

(declare impl-order-shape)

;; TODO
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

(defn impl-vertical-order
  [state id loc]
  (let [page-id (::page-id state)
        objects (get-in state [:workspace-data page-id :objects])

        frame-id (get-in objects [id :frame-id])
        shapes (get-in objects [frame-id :shapes])

        cindex (d/index-of shapes id)
        nindex (case loc
                 :top 0
                 :down (min (- (count shapes) 1) (inc cindex))
                 :up (max 0 (- cindex 1))
                 :bottom (- (count shapes) 1))]
    (update-in state [:workspace-data page-id :objects frame-id :shapes]
               (fn [shapes]
                 (let [[fst snd] (->> (remove #(= % id) shapes)
                                      (split-at nindex))]
                   (d/concat [] fst [id] snd))))))

(defn vertical-order-selected
  [loc]
  (us/verify ::loc loc)
  (ptk/reify ::vertical-order-selected-shpes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (seq (get-in state [:workspace-local :selected]))

            rchanges (mapv (fn [id]
                             (let [frame-id (get-in objects [id :frame-id])]
                               {:type :mod-obj
                                :id frame-id
                                :operations [{:type :rel-order :id id :loc loc}]}))
                           selected)
            uchanges (mapv (fn [id]
                             (let [frame-id (get-in objects [id :frame-id])
                                   shapes (get-in objects [frame-id :shapes])
                                   cindex (d/index-of shapes id)]
                               {:type :mod-obj
                                :id frame-id
                                :operations [{:type :abs-order :id id :index cindex}]}))
                           selected)]
        (rx/of (commit-changes rchanges uchanges {:commit-local? true}))))))


;; --- Change Shape Order (D&D Ordering)

(defn shape-order-change
  [id index]
  (us/verify ::us/uuid id)
  (us/verify number? index)
  (ptk/reify ::change-shape-order
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)
            obj (get-in state [:workspace-data page-id :objects id])
            frm (get-in state [:workspace-data page-id :objects (:frame-id obj)])
            shp (remove #(= % id) (:shapes frm))
            [b a] (split-at index shp)
            shp (d/concat [] b [id] a)]
        (assoc-in state [:workspace-data page-id :objects (:id frm) :shapes] shp)))))

(defn commit-shape-order-change
  [id]
  (ptk/reify ::commit-shape-order-change
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (::page-id state)
            obj (get-in state [:workspace-data pid :objects id])

            cfrm (get-in state [:workspace-data pid :objects (:frame-id obj)])
            pfrm (get-in state [:pages-data pid :objects (:frame-id obj)])

            cindex (d/index-of (:shapes cfrm) id)
            pindex (d/index-of (:shapes pfrm) id)

            rchange {:type :mod-obj
                     :id (:id cfrm)
                     :operations [{:type :abs-order :id id :index cindex}]}
            uchange {:type :mod-obj
                     :id (:id cfrm)
                     :operations [{:type :abs-order :id id :index pindex}]}]
        (rx/of (commit-changes [rchange] [uchange]))))))


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
                (let [pid (::page-id state)
                      dta (get-in state [:workspace-data pid])
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
      (let [page-id (::page-id state)
            rfn #(assoc-in %1 [:workspace-data page-id
                               :objects %2 :resize-modifier] xfmt)]
        (reduce rfn state ids)))))

(defn materialize-resize-modifier-in-bulk
  [ids]
  (ptk/reify ::materialize-resize-modifier-in-bulk
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)
            rfn (fn [state id]
                  (update-in state [:workspace-data page-id :objects id]
                             (fn [shape]
                               (let [mfr (:resize-modifier shape (gmt/matrix))]
                                 (-> (dissoc shape :resize-modifier)
                                     (geom/transform mfr))))))]
        (reduce rfn state ids)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)]
        (rx/of (diff-and-commit-changes page-id)
               (rehash-shape-frame-relationship ids))))))


(defn apply-displacement-in-bulk
  "Apply the same displacement delta to all shapes identified by the set
  if ids."
  [ids delta]
  (us/verify ::set-of-uuid ids)
  (us/verify gpt/point? delta)
  (ptk/reify ::apply-displacement-in-bulk
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)
            rfn (fn [state id]
                  (let [objects (get-in state [:workspace-data page-id :objects])
                        shape   (get objects id)
                        prev    (:displacement-modifier shape (gmt/matrix))
                        curr    (gmt/translate prev delta)]
                    (->> (assoc shape :displacement-modifier curr)
                         (assoc-in state [:workspace-data page-id :objects id]))))]
        (reduce rfn state ids)))))

(defn materialize-displacement-in-bulk
  [ids]
  (ptk/reify ::materialize-displacement-in-bulk
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)
            rfn (fn [state id]
                  (update-in state [:workspace-data page-id :objects id]
                             (fn [shape]
                               (let [mtx (:displacement-modifier shape (gmt/matrix))]
                                 (-> (dissoc shape :displacement-modifier)
                                     (geom/transform mtx))))))]
        (reduce rfn state ids)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)]
        (rx/of (diff-and-commit-changes page-id)
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
      (let [page-id (::page-id state)]
        (update-in state [:workspace-data page-id :objects id]
                   (fn [shape]
                     (let [prev (:displacement-modifier shape (gmt/matrix))
                           xfmt (gmt/translate prev delta)]
                       (assoc shape :displacement-modifier xfmt))))))))

(defn materialize-frame-displacement
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::materialize-frame-displacement
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            frame   (get objects id)
            xfmt     (or (:displacement-modifier frame) (gmt/matrix))

            frame   (-> frame
                        (dissoc :displacement-modifier)
                        (geom/transform xfmt))

            shapes  (->> (:shapes frame)
                         (map #(get objects %))
                         (map #(geom/transform % xfmt))
                         (d/index-by :id))

            shapes (assoc shapes (:id frame) frame)]
        (update-in state [:workspace-data page-id :objects] merge shapes)))))


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
     cljs.core/IDeref
     (-deref [_] changes)

     ptk/UpdateEvent
     (update [_ state]
       (let [page-id (::page-id state)
             state (update-in state [:pages-data page-id] cp/process-changes changes)]
         (cond-> state
           commit-local? (update-in [:workspace-data page-id] cp/process-changes changes))))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [page (:workspace-page state)
             uidx (get-in state [:workspace-local :undo-index] ::not-found)]
         (rx/concat
          (when (and save-undo? (not= uidx ::not-found))
            (rx/of (reset-undo uidx)))

          (when save-undo?
            (let [entry {:undo-changes undo-changes
                         :redo-changes changes}]
              (rx/of (append-undo entry))))))))))


(s/def ::shapes-changes-commited
  (s/keys :req-un [::page-id ::revn ::cp/changes]))

(defn shapes-changes-commited
  [{:keys [page-id revn changes] :as params}]
  (us/verify ::shapes-changes-commited params)
  (ptk/reify ::changes-commited
    ptk/UpdateEvent
    (update [_ state]
      (let [session-id (:session-id state)
            state (-> state
                      (assoc-in [:pages page-id :revn] revn))
            changes (filter #(not= session-id (:session-id %)) changes)]
        (-> state
            (update-in [:workspace-data page-id] cp/process-changes changes)
            (update-in [:pages-data page-id] cp/process-changes changes))))))

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
      (let [page-id (::page-id state)]
        (update-in state [:workspace-data page-id :objects id]
                   geom/resize-rect attr value)))))

(defn update-circle-dimensions
  [id attr value]
  (us/verify ::us/uuid id)
  (us/verify #{::rx ::ry} attr)
  (us/verify ::us/number value)
  (ptk/reify ::update-rect-dimensions
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)]
        (update-in state [:workspace-data page-id :objects id]
                   geom/resize-rect attr value)))))

;; --- Shape Proportions

(defn toggle-shape-proportion-lock
  [id]
  (ptk/reify ::toggle-shape-proportion-lock
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)
            shape (get-in state [:workspace-data page-id :objects id])]
        (if (:proportion-lock shape)
          (assoc-in state [:workspace-data page-id :objects id :proportion-lock] false)
          (->> (geom/assign-proportions (assoc shape :proportion-lock true))
               (assoc-in state [:workspace-data page-id :objects id])))))))

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
      (let [page-id (::page-id state)]
        (update-in state [:workspace-data page-id :objects id]
                   geom/absolute-move position)))))

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
      (let [page-id (::page-id state)]
        (update-in state [:workspace-data page-id :objects id :segments index] gpt/add delta)))))

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
            page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            frame   (get objects id)]
        (reduce hide state (cons id (:shapes frame)))))))

(defn show-frame
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::hide-shape
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [show #(impl-update-shape-hidden %1 %2 false)
            page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            frame   (get objects id)]
        (reduce show state (cons id (:shapes frame)))))))

(defn- impl-update-shape-hidden
  [state id hidden?]
  (let [page-id (::page-id state)]
    (assoc-in state [:workspace-data page-id :objects id :hidden] hidden?)))

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
  [state id blocked?]
  (let [page-id (::page-id state)
        obj (get-in state [:workspace-data page-id :objects id])
        obj (assoc obj :blocked blocked?)
        state (assoc-in state [:workspace-data page-id :objects id] obj)]
    (if (= :frame (:type obj))
      (update-in state [:workspace-data page-id :objects]
                 (fn [objects]
                   (reduce #(update %1 %2 assoc :blocked blocked?) objects (:shapes obj))))
      state)))


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
;; Context Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::point gpt/point?)

(defn show-context-menu
  [{:keys [position] :as params}]
  (us/verify ::point position)
  (ptk/reify ::show-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] {:position position}))))

(defn show-shape-context-menu
  [{:keys [position shape] :as params}]
  (us/verify ::point position)
  (us/verify ::cp/minimal-shape shape)
  (ptk/reify ::show-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:workspace-local :selected])
            selected (cond
                       (empty? selected)
                       (conj selected (:id shape))

                       (contains? selected (:id shape))
                       selected

                       :else
                       #{(:id shape)})
            mdata {:position position
                   :selected selected
                   :shape shape}]
        (-> state
            (assoc-in [:workspace-local :context-menu] mdata)
            (assoc-in [:workspace-local :selected] selected))))))

(def hide-context-menu
  (ptk/reify ::hide-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] nil))))


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

