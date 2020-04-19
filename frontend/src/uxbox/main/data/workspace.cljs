;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [goog.events :as events]
   [goog.object :as gobj]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.main.constants :as c]
   [uxbox.main.data.dashboard :as dd]
   [uxbox.main.data.helpers :as helpers]
   [uxbox.main.data.icons :as udi]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.websockets :as ws]
   [uxbox.main.worker :as uw]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.math :as mth]
   [uxbox.util.perf :as perf]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]
   [uxbox.util.transit :as t]
   [uxbox.util.webapi :as wapi]))

;; TODO: temporal workaround
(def clear-ruler nil)
(def start-ruler nil)

;; --- Specs

(s/def ::shape-attrs ::cp/shape-attrs)

(s/def ::set-of-uuid
  (s/every uuid? :kind set?))
(s/def ::set-of-string
  (s/every string? :kind set?))

;; --- Expose inner functions

(defn interrupt? [e] (= e :interrupt))

;; --- Protocols

(defprotocol IBatchedChange)

;; --- Declarations

(declare fetch-project)
(declare handle-who)
(declare handle-pointer-update)
(declare handle-pointer-send)
(declare handle-page-change)
(declare shapes-changes-commited)
(declare commit-changes)
(declare fetch-bundle)
(declare initialize-ws)
(declare finalize-ws)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialize Workspace

(def default-layout
  #{:sitemap
    :sitemap-pages
    :layers
    :element-options
    :rules})

(def workspace-default
  {:zoom 1
   :flags #{}
   :selected #{}
   :drawing nil
   :drawing-tool nil
   :tooltip nil})

(def initialize-layout
  (ptk/reify ::initialize-layout
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-layout default-layout))))

(defn initialized
  [project-id file-id]
  (ptk/reify ::initialized
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-file
              (fn [file]
                (if (= (:id file) file-id)
                  (assoc file :initialized true)
                  file))))))
(defn initialize
  [project-id file-id]
  (us/verify ::us/uuid project-id)
  (us/verify ::us/uuid file-id)

  (letfn [(setup-index [{:keys [file pages] :as params}]
            (let [msg {:cmd :selection/create-index
                       :file-id (:id file)
                       :pages pages}]
              (->> (uw/ask! msg)
                   (rx/map (constantly ::index-initialized)))))]

    (ptk/reify ::initialize
      ptk/WatchEvent
      (watch [_ state stream]
        (rx/merge
         (rx/of (fetch-bundle project-id file-id)
                (initialize-ws file-id))

         (->> stream
              (rx/filter (ptk/type? ::bundle-fetched))
              (rx/map deref)
              (rx/mapcat setup-index)
              (rx/first))

         (->> stream
              (rx/filter #(= ::index-initialized %))
              (rx/map (constantly
                       (initialized project-id file-id)))))))))


(defn finalize
  [project-id file-id]
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :workspace-file :workspace-project))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (finalize-ws file-id)))))


(declare initialize-page-persistence)

(defn initialize-page
  [page-id]
  (ptk/reify ::initialize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [page  (get-in state [:workspace-pages page-id])
            local (get-in state [:workspace-cache page-id] workspace-default)]
        (-> state
            (assoc ::page-id page-id   ; mainly used by events
                   :workspace-local local
                   :workspace-page (dissoc page :data))
            (assoc-in [:workspace-data page-id] (:data page)))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (initialize-page-persistence page-id)))))

(defn finalize-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::finalize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (:workspace-local state)]
        (-> state
            (assoc-in [:workspace-cache page-id] local)
            (update :workspace-data dissoc page-id))))))

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
        color "#000000" #_(js/randomcolor)
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
;; Data Persistence
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare persist-changes)
(declare diff-and-commit-changes)

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
            page (get-in state [:workspace-pages page-id])
            changes (->> changes
                         (mapcat identity)
                         (map #(assoc % :session-id session-id))
                         (vec))
            params {:id (:id page)
                    :revn (:revn page)
                    :changes changes}]
        (->> (rp/mutation :update-page params)
             (rx/map shapes-changes-commited))))))


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
            prev (get-in state [:workspace-pages page-id :data])

            changes (generate-changes prev curr)
            undo-changes (generate-changes curr prev)]
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

(s/def ::file ::dd/file)
(s/def ::project ::dd/project)
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

;; --- Fetch Workspace Bundle

(declare bundle-fetched)

(defn- fetch-bundle
  [project-id file-id]
  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/zip (rp/query :file-with-users {:id file-id})
                   (rp/query :project-by-id {:project-id project-id})
                   (rp/query :pages {:file-id file-id}))
           (rx/first)
           (rx/map (fn [[file project pages]]
                     (bundle-fetched file project pages)))
           (rx/catch (fn [{:keys [type] :as error}]
                       (when (= :not-found type)
                         (rx/of (rt/nav :not-found)))))))))

(defn- bundle-fetched
  [file project pages]
  (ptk/reify ::bundle-fetched
    IDeref
    (-deref [_]
      {:file file
       :project project
       :pages pages})

    ptk/UpdateEvent
    (update [_ state]
      (let [assoc-page #(assoc-in %1 [:workspace-pages (:id %2)] %2)]
        (as-> state $$
          (assoc $$
                 :workspace-file file
                 :workspace-pages {}
                 :workspace-project project)
          (reduce assoc-page $$ pages))))))

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
            name (str "Page " (gensym "p"))
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
      (let [pid (get-in state [:workspac-page :id])
            state (assoc-in state [:workspac-pages id :name] name)]
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

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (-> state
      (update-in [:workspace-file :pages] #(filterv (partial not= id) %))
      (update :workspace-pages dissoc id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace State Manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Toggle layout flag

(defn toggle-layout-flag
  [& flags]
  (ptk/reify ::toggle-layout-flag
    ptk/UpdateEvent
    (update [_ state]
      (let [reduce-fn
            (fn [state flag]
              (update state :workspace-layout
                      (fn [flags]
                        (if (contains? flags flag)
                          (disj flags flag)
                          (conj flags flag)))))]
        (reduce reduce-fn state flags)))))

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

(def zoom-to-50
  (ptk/reify ::zoom-to-50
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :zoom] 0.5))))

(def zoom-to-200
  (ptk/reify ::zoom-to-200
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :zoom] 2))))

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
                (rx/filter #(or (> (:width %) 10)
                                (> (:height %) 10)))
                (rx/map update-selrect)
                (rx/take-until stoper))
           (rx/of select-shapes-by-current-selrect)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shapes events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn select-shapes
  [ids]
  (us/verify ::set-of-uuid ids)
  (ptk/reify ::select-shapes
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selected] ids))))

(def deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user."
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local #(-> %
                                          (assoc :selected #{})
                                          (dissoc :selected-frame))))))


;; --- Add shape to Workspace

(defn- retrieve-used-names
  [objects]
  (into #{} (map :name) (vals objects)))

(defn- extract-numeric-suffix
  [basename]
  (if-let [[match p1 p2] (re-find #"(.*)-([0-9]+)$" basename)]
    [p1 (+ 1 (d/parse-integer p2))]
    [basename 1]))

(defn- generate-unique-name
  "A unique name generator"
  [used basename]
  (s/assert ::set-of-string used)
  (s/assert ::us/string basename)
  (let [[prefix initial] (extract-numeric-suffix basename)]
    (loop [counter initial]
      (let [candidate (str prefix "-" counter)]
        (if (contains? used candidate)
          (recur (inc counter))
          candidate)))))

(defn- calculate-frame-overlap
  [objects shape]
  (let [rshp (geom/shape->rect-shape shape)

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
  (ptk/reify ::add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])

            id       (uuid/next)
            shape    (geom/setup-proportions attrs)

            unames   (retrieve-used-names objects)
            name     (generate-unique-name unames (:name shape))

            frame-id (if (= :frame (:type shape))
                       uuid/zero
                       (calculate-frame-overlap objects shape))

            shape    (merge
                      (if (= :frame (:type shape))
                        cp/default-frame-attrs
                        cp/default-shape-attrs)
                      (assoc shape
                             :id id
                             :name name
                             :frame-id frame-id))

            rchange  {:type :add-obj
                      :id id
                      :frame-id frame-id
                      :obj shape}
            uchange  {:type :del-obj
                      :id id}]

        (rx/of (commit-changes [rchange] [uchange] {:commit-local? true})
               (select-shapes #{id}))))))


;; --- Duplicate Shapes

(declare prepare-duplicate-changes)
(declare prepare-duplicate-change)
(declare prepare-duplicate-frame-change)
(declare prepare-duplicate-shape-change)

(def ^:private change->name #(get-in % [:obj :name]))

(defn- prepare-duplicate-changes
  "Prepare objects to paste: generate new id, give them unique names,
  move to the position of mouse pointer, and find in what frame they
  fit."
  [objects names ids delta]
  (loop [names names
         chgs []
         id   (first ids)
         ids  (rest ids)]
    (if (nil? id)
      chgs
      (let [result (prepare-duplicate-change objects names id delta)
            result (if (vector? result) result [result])]
        (recur
         (into names (map change->name) result)
         (into chgs result)
         (first ids)
         (rest ids))))))

(defn- prepare-duplicate-change
  [objects names id delta]
  (let [obj (get objects id)]
    (if (= :frame (:type obj))
      (prepare-duplicate-frame-change objects names obj delta)
      (prepare-duplicate-shape-change objects names obj delta nil nil))))

(defn- prepare-duplicate-shape-change
  [objects names obj delta frame-id parent-id]
  (let [id (uuid/next)
        name (generate-unique-name names (:name obj))
        renamed-obj (assoc obj :id id :name name)
        moved-obj (geom/move renamed-obj delta)
        frame-id (if frame-id
                   frame-id
                   (calculate-frame-overlap objects moved-obj))

        parent-id (or parent-id frame-id)

        children-changes
        (loop [names names
               result []
               cid (first (:shapes obj))
               cids (rest (:shapes obj))]
          (if (nil? cid)
            result
            (let [obj (get objects cid)
                  changes (prepare-duplicate-shape-change objects names obj delta frame-id id)]
              (recur
               (into names (map change->name changes))
               (into result changes)
               (first cids)
               (rest cids)))))

        reframed-obj (-> moved-obj
                         (assoc  :frame-id frame-id)
                         (dissoc :shapes))]
    (into [{:type :add-obj
            :id id
            :old-id (:id obj)
            :frame-id frame-id
            :parent-id parent-id
            :obj (dissoc reframed-obj :shapes)}]
          children-changes)))

(defn- prepare-duplicate-frame-change
  [objects names obj delta]
  (let [frame-id   (uuid/next)
        frame-name (generate-unique-name names (:name obj))
        sch (->> (map #(get objects %) (:shapes obj))
                 (mapcat #(prepare-duplicate-shape-change objects names % delta frame-id frame-id)))

        renamed-frame (-> obj
                          (assoc :id frame-id)
                          (assoc :name frame-name)
                          (assoc :frame-id uuid/zero)
                          (dissoc :shapes))

        moved-frame (geom/move renamed-frame delta)

        fch {:type :add-obj
             :old-id (:id obj)
             :id frame-id
             :frame-id uuid/zero
             :obj moved-frame}]

    (into [fch] sch)))

(declare select-shapes)

(def duplicate-selected
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            selected (get-in state [:workspace-local :selected])
            objects (get-in state [:workspace-data page-id :objects])
            delta (gpt/point 0 0)
            unames (retrieve-used-names objects)

            rchanges (prepare-duplicate-changes objects unames selected delta)
            uchanges (mapv #(array-map :type :del-obj :id (:id %))
                           (reverse rchanges))

            selected (->> rchanges
                          (filter #(selected (:old-id %)))
                          (map #(get-in % [:obj :id]))
                          (into #{}))]

        (rx/of (commit-changes rchanges uchanges {:commit-local? true})
               (select-shapes selected))))))


;; --- Select Shapes (By selrect)

(def select-shapes-by-current-selrect
  (ptk/reify ::select-shapes-by-current-selrect
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (get-in state [:workspace-page :id])
            selrect (get-in state [:workspace-local :selrect])]
        (rx/merge
         (rx/of (update-selrect nil))
         (when selrect
           (->> (uw/ask! {:cmd :selection/query
                          :page-id page-id
                          :rect selrect})
                (rx/map select-shapes))))))))

(defn select-inside-group
  [group-id position]
  (ptk/reify ::select-inside-group
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            group (get objects group-id)
            children (map #(get objects %) (:shapes group))
            selected (->> children (filter #(geom/has-point? % position)) first)]
        (cond-> state
          selected (assoc-in [:workspace-local :selected] #{(:id selected)}))))))

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
            cpindex (helpers/calculate-child-parent-map objects)

            del-change #(array-map :type :del-obj :id %)

            rchanges
            (reduce (fn [res id]
                      (let [chd (helpers/get-children id objects)]
                        (into res (d/concat
                                   (mapv del-change (reverse chd))
                                   [(del-change id)]))))
                    []
                    ids)

            uchanges
            (mapv (fn [id]
                    (let [obj (get objects id)]
                     {:type :add-obj
                      :id id
                      :frame-id (:frame-id obj)
                      :parent-id (get cpindex id)
                      :obj obj}))
                  (reverse (map :id rchanges)))]
        (rx/of (commit-changes rchanges uchanges {:commit-local? true}))))))

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
        (rx/of (delete-shapes selected))))))


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

;; TODO: pending UNDO

(defn relocate-shape
  [id ref-id index]
  (us/verify ::us/uuid id)
  (us/verify ::us/uuid ref-id)
  (us/verify number? index)

  (ptk/reify ::relocate-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            selected (get-in state [:workspace-local :selected])
            objects (get-in state [:workspace-data page-id :objects])
            parent-id (helpers/get-parent ref-id objects)]
        (rx/of (commit-changes [{:type :mov-objects
                                 :parent-id parent-id
                                 :index index
                                 :shapes (vec selected)}]
                               []
                               {:commit-local? true}))))))


;; --- Shape / Selection Alignment and Distribution

(declare align-object-to-frame)
(declare align-objects-list)

(defn align-objects
  [axis]
  (us/verify ::geom/align-axis axis)
  (ptk/reify :align-objects
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            moved-objs (if (= 1 (count selected))
                         (align-object-to-frame objects (first selected) axis)
                         (align-objects-list objects selected axis))
            updated-objs (merge objects (d/index-by :id moved-objs))]
        (assoc-in state [:workspace-data page-id :objects] updated-objs)))))

(defn align-object-to-frame
  [objects object-id axis]
  (let [object (get objects object-id)
        frame (get objects (:frame-id object))]
    (geom/align-to-rect object frame axis objects)))

(defn align-objects-list
  [objects selected axis]
  (let [selected-objs (map #(get objects %) selected)
        rect (geom/selection-rect selected-objs)]
    (mapcat #(geom/align-to-rect % rect axis objects) selected-objs)))

(defn distribute-objects
  [axis]
  (us/verify ::geom/dist-axis axis)
  (ptk/reify :align-objects
    IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            selected-objs (map #(get objects %) selected)
            moved-objs (geom/distribute-space selected-objs axis objects)
            updated-objs (merge objects (d/index-by :id moved-objs))]
        (assoc-in state [:workspace-data page-id :objects] updated-objs)))))


;; --- Temportal displacement for Shape / Selection

(defn- retrieve-toplevel-shapes
  [objects]
  (let [lookup #(get objects %)
        root   (lookup uuid/zero)
        childs (:shapes root)]
    (loop [id  (first childs)
           ids (rest childs)
           res []]
      (if (nil? id)
        res
        (let [obj (lookup id)
              typ (:type obj)]
          (recur (first ids)
                 (rest ids)
                 (if (= :frame typ)
                   (into res (:shapes obj))
                   (conj res id))))))))

(defn- calculate-shape-to-frame-relationship-changes
  [objects ids]
  (loop [id  (first ids)
         ids (rest ids)
         rch []
         uch []]
    (if (nil? id)
      [rch uch]
      (let [obj (get objects id)
            fid (calculate-frame-overlap objects obj)]
        (if (not= fid (:frame-id obj))
          (recur (first ids)
                 (rest ids)
                 (conj rch {:type :mov-objects
                            :parent-id fid
                            :shapes [id]})
                 (conj uch {:type :mov-objects
                            :parent-id (:frame-id obj)
                            :shapes [id]}))
          (recur (first ids)
                 (rest ids)
                 rch
                 uch))))))

(defn- rehash-shape-frame-relationship
  [ids]
  (ptk/reify ::rehash-shape-frame-relationship
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (::page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            ids     (retrieve-toplevel-shapes objects)
            [rch uch] (calculate-shape-to-frame-relationship-changes objects ids)
            ]

        (when-not (empty? rch)
          (rx/of (commit-changes rch uch {:commit-local? true})))))))

(defn- adjust-group-shapes
  [state ids]
  (let [page-id (::page-id state)
        objects (get-in state [:workspace-data page-id :objects])
        groups-to-adjust (->> ids
                              (mapcat #(reverse (helpers/get-all-parents % objects)))
                              (map #(get objects %))
                              (filter #(= (:type %) :group))
                              (map #(:id %))
                              distinct)

        update-group
        (fn [state group]
          (let [objects (get-in state [:workspace-data page-id :objects])
                group-objects (map #(get objects %) (:shapes group))
                selrect (geom/selection-rect group-objects)]
            (merge group (select-keys selrect [:x :y :width :height]))))

        reduce-fn
        #(update-in %1 [:workspace-data page-id :objects %2] (partial update-group %1))]

    (reduce reduce-fn state groups-to-adjust)))

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
            objects (get-in state [:workspace-data page-id :objects])

            ;; Updates the resize data for a single shape
            materialize-shape
            (fn [state id mtx]
              (update-in
               state
               [:workspace-data page-id :objects id]
               #(-> %
                    (dissoc :resize-modifier)
                    (geom/transform mtx))))

            ;; Applies materialize-shape over shape children
            materialize-children
            (fn [state id mtx]
              (reduce #(materialize-shape %1 %2 mtx) state (helpers/get-children id objects)))

            ;; For each shape makes permanent the displacemnt
            update-shapes
            (fn [state id]
              (let [shape (get objects id)
                    mtx (:resize-modifier shape (gmt/matrix))]
                (if (= (:type shape) :frame)
                  (materialize-shape state id mtx)
                  (-> state
                      (materialize-shape id mtx)
                      (materialize-children id mtx)))))]

        (as-> state $
          (reduce update-shapes $ ids)
          (adjust-group-shapes $ ids))))

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
            objects (get-in state [:workspace-data page-id :objects])

            ;; Updates the displacement data for a single shape
            materialize-shape
            (fn [state id mtx]
              (update-in
               state
               [:workspace-data page-id :objects id]
               #(-> %
                    (dissoc :displacement-modifier)
                    (geom/transform mtx))))

            ;; Applies materialize-shape over shape children
            materialize-children
            (fn [state id mtx]
              (reduce #(materialize-shape %1 %2 mtx) state (helpers/get-children id objects)))

            ;; For each shape makes permanent the resize
            update-shapes
            (fn [state id]
              (let [shape (get objects id)
                    mtx (:displacement-modifier shape (gmt/matrix))]
                (-> state
                    (materialize-shape id mtx)
                    (materialize-children id mtx))))]

        (as-> state $
          (reduce update-shapes $ ids)
          (adjust-group-shapes $ ids))))

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

            shapes  (->> (helpers/get-children id objects)
                         (map #(get objects %))
                         (map #(geom/transform % xfmt))
                         (d/index-by :id))

            shapes (assoc shapes (:id frame) frame)]
        (update-in state [:workspace-data page-id :objects] merge shapes)))))


(defn apply-rotation
  [delta-rotation shapes]
  (ptk/reify ::apply-rotation
    ptk/UpdateEvent
    (update [_ state]
      (let [group  (geom/selection-rect shapes)
            group-center (gpt/center group)
            calculate-displacement
            (fn [shape angle]
              (let [shape-rect (geom/shape->rect-shape shape)
                    shape-center (gpt/center shape-rect)]
                (-> (gmt/matrix)
                    (gmt/rotate angle group-center)
                    (gmt/rotate (- angle) shape-center))))

            page-id (::page-id state)
            rotate-shape
            (fn [state shape]
              (let [path [:workspace-data page-id :objects (:id shape)]
                    ds (calculate-displacement shape delta-rotation)]
                (-> state
                    (assoc-in (conj path :rotation-modifier) delta-rotation)
                    (assoc-in (conj path :displacement-modifier) ds))))]
        (reduce rotate-shape state shapes)))))

(defn materialize-rotation
  [shapes]
  (ptk/reify ::materialize-rotation
    IBatchedChange

    ptk/UpdateEvent
    (update [_ state]
      (let [apply-rotation
            (fn [shape]
              (let [ds-modifier (or (:displacement-modifier shape) (gmt/matrix))]
                (-> shape
                    (update :rotation #(mod (+ % (:rotation-modifier shape)) 360))
                    (geom/transform ds-modifier)
                    (dissoc :rotation-modifier)
                    (dissoc :displacement-modifier))))

            materialize-shape
            (fn [state shape]
              (let [path [:workspace-data (::page-id state) :objects (:id shape)]]
                (update-in state path apply-rotation)))]

        (reduce materialize-shape state shapes)))))

(defn- update-selection-index
  [page-id]
  (ptk/reify ::update-selection-index
    ptk/EffectEvent
    (effect [_ state stream]
      (let [objects (get-in state [:workspace-pages page-id :data :objects])
            lookup  #(get objects %)]
        (uw/ask! {:cmd :selection/update-index
                  :page-id page-id
                  :objects objects})))))

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
             state (update-in state [:workspace-pages page-id :data] cp/process-changes changes)]
         (cond-> state
           commit-local? (update-in [:workspace-data page-id] cp/process-changes changes))))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [page (:workspace-page state)
             uidx (get-in state [:workspace-local :undo-index] ::not-found)]
         (rx/concat
          (rx/of (update-selection-index (:id page)))

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
                      (assoc-in [:workspace-pages page-id :revn] revn))
            changes (filter #(not= session-id (:session-id %)) changes)]
        (-> state
            (update-in [:workspace-data page-id] cp/process-changes changes)
            (update-in [:workspace-pages page-id :data] cp/process-changes changes))))))

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
           (rx/filter interrupt?)
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
        (update-in state [:workspace-data page-id :objects id :segments index]
                   gpt/add delta)))))

;; --- Shape attrs (Layers Sidebar)

(defn toggle-collapse
  [id]
  (ptk/reify ::toggle-collapse
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :expanded id] not))))

(def collapse-all
  (ptk/reify ::collapse-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :expanded))))

(defn recursive-assign
  "A helper for assign recursively a shape attr."
  [id attr value]
  (ptk/reify ::recursive-assign
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (get-in state [:workspace-page :id])
            objects (get-in state [:workspace-data page-id :objects])
            childs (helpers/get-children id objects)]
        (update-in state [:workspace-data page-id :objects]
                   (fn [objects]
                     (reduce (fn [objects id]
                               (assoc-in objects [id attr] value))
                             objects
                             (conj childs id))))))))

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
      (let [project-id (get-in state [:workspace-project :id])
            file-id (get-in state [:workspace-page :file-id])
            path-params {:file-id file-id :project-id project-id}
            query-params {:page-id page-id}]
        (rx/of (rt/nav :workspace path-params query-params))))))

(def go-to-file
  (ptk/reify ::go-to-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (:workspace-file state)

            file-id (:id file)
            project-id (:project-id file)
            page-ids (:pages file)

            path-params {:project-id project-id :file-id file-id}
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
;; Clipboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def copy-selected
  (letfn [(prepare-selected [objects selected]
            (let [data (reduce #(prepare %1 objects %2) {} selected)]
              {:type :copied-shapes
               :selected selected
               :objects data}))

          (prepare [result objects id]
            (let [obj (get objects id)]
              (as-> result $$
                (assoc $$ id obj)
                (reduce #(prepare %1 objects %2) $$ (:shapes obj)))))

          (on-copy-error [error]
            (js/console.error "Clipboard blocked:" error)
            (rx/empty))]

    (ptk/reify ::copy-selected
      ptk/WatchEvent
      (watch [_ state stream]
        (let [page-id (::page-id state)
              objects (get-in state [:workspace-data page-id :objects])
              selected (get-in state [:workspace-local :selected])
              cdata    (prepare-selected objects selected)]
          (->> (t/encode cdata)
               (wapi/write-to-clipboard)
               (rx/from)
               (rx/catch on-copy-error)
               (rx/ignore)))))))

(defn- paste-impl
  [{:keys [selected objects] :as data}]
  (ptk/reify ::paste-impl
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected-objs (map #(get objects %) selected)
            wrapper (geom/selection-rect selected-objs)
            orig-pos (gpt/point (:x1 wrapper) (:y1 wrapper))
            mouse-pos @ms/mouse-position
            delta (gpt/subtract mouse-pos orig-pos)

            page-id (::page-id state)
            unames (-> (get-in state [:workspace-data page-id :objects])
                       (retrieve-used-names))

            rchanges (prepare-duplicate-changes objects unames selected delta)
            uchanges (mapv #(array-map :type :del-obj :id (:id %))
                           (reverse rchanges))

            selected (->> rchanges
                          (filter #(selected (:old-id %)))
                          (map #(get-in % [:obj :id]))
                          (into #{}))]
        (rx/of (commit-changes rchanges uchanges {:commit-local? true})
               (select-shapes selected))))))

(def paste
  (ptk/reify ::paste
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/from (wapi/read-from-clipboard))
           (rx/map t/decode)
           (rx/filter #(= :copied-shapes (:type %)))
           (rx/map #(select-keys % [:selected :objects]))
           (rx/map paste-impl)
           (rx/catch (fn [err]
                       (js/console.error "Clipboard error:" err)
                       (rx/empty)))))))


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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GROUPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn group-shape
  [id frame-id selected selection-rect]
  {:id id
   :type :group
   :name (name (gensym "Group-"))
   :shapes []
   :frame-id frame-id
   :x (:x selection-rect)
   :y (:y selection-rect)
   :width (:width selection-rect)
   :height (:height selection-rect)})

(def create-group
  (ptk/reify ::create-group
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (uuid/next)
            selected (get-in state [:workspace-local :selected])]
        (when (not-empty selected)
          (let [page-id (get-in state [:workspace-page :id])
                objects (get-in state [:workspace-data page-id :objects])
                selected-objects (map (partial get objects) selected)
                selection-rect (geom/selection-rect selected-objects)
                frame-id (-> selected-objects first :frame-id)
                group-shape (group-shape id frame-id selected selection-rect)
                frame-children (get-in objects [frame-id :shapes])
                index-frame (->> frame-children
                                 (map-indexed vector)
                                 (filter #(selected (second %)))
                                 (ffirst))

                rchanges [{:type :add-obj
                           :id id
                           :frame-id frame-id
                           :obj group-shape
                           :index index-frame}
                          {:type :mov-objects
                           :parent-id id
                           :shapes (vec selected)}]
                uchanges [{:type :mov-objects
                           :parent-id frame-id
                           :shapes (vec selected)}
                          {:type :del-obj
                           :id id}]]
            (rx/of (commit-changes rchanges uchanges {:commit-local? true})
                   (select-shapes #{id}))))))))

(def remove-group
  (ptk/reify ::remove-group
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (::page-id state)
            objects  (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            group-id (first selected)
            group    (get objects group-id)]
        (when (and (= 1 (count selected))
                   (= (:type group) :group))
          (let [shapes    (:shapes group)
                parent-id (helpers/get-parent group-id objects)
                parent    (get objects parent-id)
                index-in-parent (->> (:shapes parent)
                                     (map-indexed vector)
                                     (filter #(#{group-id} (second %)))
                                     (ffirst))
                rchanges [{:type :mov-objects
                           :parent-id parent-id
                           :shapes shapes
                           :index index-in-parent}]
                uchanges [{:type :add-obj
                           :id group-id
                           :frame-id (:frame-id group)
                           :obj (assoc group :shapes [])}
                          {:type :mov-objects
                           :parent-id group-id
                           :shapes shapes}
                          {:type :mov-objects
                           :parent-id parent-id
                           :shapes [group-id]
                           :index index-in-parent}]]
            (rx/of (commit-changes rchanges uchanges {:commit-local? true}))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts impl https://github.com/ccampbell/mousetrap

(def shortcuts
  {"ctrl+shift+m" #(st/emit! (toggle-layout-flag :sitemap))
   "ctrl+shift+i" #(st/emit! (toggle-layout-flag :libraries))
   "ctrl+shift+l" #(st/emit! (toggle-layout-flag :layers))
   "+" #(st/emit! increase-zoom)
   "-" #(st/emit! decrease-zoom)
   "ctrl+g" #(st/emit! create-group)
   "ctrl+shift+g" #(st/emit! remove-group)
   "shift+0" #(st/emit! zoom-to-50)
   "shift+1" #(st/emit! reset-zoom)
   "shift+2" #(st/emit! zoom-to-200)
   "ctrl+d" #(st/emit! duplicate-selected)
   "ctrl+z" #(st/emit! undo)
   "ctrl+shift+z" #(st/emit! redo)
   "ctrl+y" #(st/emit! redo)
   "ctrl+q" #(st/emit! reinitialize-undo)
   "ctrl+b" #(st/emit! (select-for-drawing :rect))
   "ctrl+e" #(st/emit! (select-for-drawing :circle))
   "ctrl+t" #(st/emit! (select-for-drawing :text))
   "ctrl+c" #(st/emit! copy-selected)
   "ctrl+v" #(st/emit! paste)
   "escape" #(st/emit! :interrupt deselect-all)
   "del" #(st/emit! delete-selected)
   "ctrl+up" #(st/emit! (vertical-order-selected :up))
   "ctrl+down" #(st/emit! (vertical-order-selected :down))
   "ctrl+shift+up" #(st/emit! (vertical-order-selected :top))
   "ctrl+shift+down" #(st/emit! (vertical-order-selected :bottom))
   "shift+up" #(st/emit! (move-selected :up true))
   "shift+down" #(st/emit! (move-selected :down true))
   "shift+right" #(st/emit! (move-selected :right true))
   "shift+left" #(st/emit! (move-selected :left true))
   "up" #(st/emit! (move-selected :up false))
   "down" #(st/emit! (move-selected :down false))
   "right" #(st/emit! (move-selected :right false))
   "left" #(st/emit! (move-selected :left false))})
