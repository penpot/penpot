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
   [uxbox.util.geom.shapes :as geom]
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
   [uxbox.util.webapi :as wapi]
   [uxbox.util.avatars :as avatars]
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.data.workspace.transforms :as transforms]))

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

;; --- Declarations

(declare fetch-project)
(declare handle-presence)
(declare handle-pointer-update)
(declare handle-pointer-send)
(declare handle-page-change)
(declare shapes-changes-commited)
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
      ptk/UpdateEvent
      (update [_ state]
        (assoc state :workspace-presence {}))

      ptk/WatchEvent
      (watch [_ state stream]
        (rx/merge
         (rx/of (fetch-bundle project-id file-id))

         (->> stream
              (rx/filter (ptk/type? ::bundle-fetched))
              (rx/mapcat (fn [_] (rx/of (initialize-ws file-id))))
              (rx/first))

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
(declare initialize-group-check)

(defn initialize-page
  [page-id]
  (ptk/reify ::initialize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [page  (get-in state [:workspace-pages page-id])
            local (get-in state [:workspace-cache page-id] workspace-default)]
        (-> state
            (assoc :current-page-id page-id   ; mainly used by events
                   :workspace-local local
                   :workspace-page (dissoc page :data))
            (assoc-in [:workspace-data page-id] (:data page)))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (initialize-page-persistence page-id)
             (initialize-group-check)))))

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

(declare adjust-group-shapes)

(defn initialize-group-check []
  (ptk/reify ::initialize-group-check
    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter #(satisfies? dwc/IUpdateGroup %))
           (rx/map #(adjust-group-shapes (dwc/get-ids %)))))))

(defn adjust-group-shapes
  [ids]
  (ptk/reify ::adjust-group-shapes
    dwc/IBatchedChange

    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
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
                    group-center (geom/center group)
                    group-objects (->> (:shapes group)
                                       (map #(get objects %))
                                       (map #(-> %
                                                 (assoc :modifiers
                                                        (transforms/rotation-modifiers group-center % (- (:rotation group 0))))
                                                 (geom/transform-shape))))
                    selrect (geom/selection-rect group-objects)]

                ;; Rotate the group shape change the data and rotate back again
                (-> group
                    (assoc-in [:modifiers :rotation] (- (:rotation group)))
                    (geom/transform-shape)
                    (merge (select-keys selrect [:x :y :width :height]))
                    (assoc-in [:modifiers :rotation] (:rotation group))
                    (geom/transform-shape))))

            reduce-fn
            #(update-in %1 [:workspace-data page-id :objects %2] (partial update-group %1))]

        (reduce reduce-fn state groups-to-adjust)))))

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
      (let [sid (:session-id state)
            url (ws/url (str "/notifications/" file-id "/" sid))]
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
                               :presence (handle-presence msg)
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

;; --- Handle: Presence

(def ^:private presence-palette
  #{"#2e8b57" ; seagreen
    "#808000" ; olive
    "#b22222" ; firebrick
    "#ff8c00" ; darkorage
    "#ffd700" ; gold
    "#ba55d3" ; mediumorchid
    "#00fa9a" ; mediumspringgreen
    "#00bfff" ; deepskyblue
    "#dda0dd" ; plum
    "#ff1493" ; deeppink
    "#ffa07a" ; lightsalmon
    })

(defn handle-presence
  [{:keys [sessions] :as msg}]
  (letfn [(assign-color [sessions session]
            (if (string? (:color session))
              session
              (let [used (into #{}
                               (comp (map second)
                                     (map :color)
                                     (remove nil?))
                               sessions)
                    avail (set/difference presence-palette used)
                    color (or (first avail) "#000000")]
                (assoc session :color color))))
          (update-sessions [previous profiles]
            (reduce (fn [current [session-id profile-id]]
                      (let [profile (get profiles profile-id)
                            session {:id session-id
                                     :fullname (:fullname profile)
                                     :photo-uri (or (:photo-uri profile)
                                                    (avatars/generate {:name (:fullname profile)}))}
                            session (assign-color current session)]
                        (assoc current session-id session)))
                    (select-keys previous (map first sessions))
                    (filter (fn [[sid]] (not (contains? previous sid))) sessions)))]

    (ptk/reify ::handle-presence
      ptk/UpdateEvent
      (update [_ state]
        (let [profiles  (:workspace-users state)]
          (update state :workspace-presence update-sessions profiles))))))

(defn handle-pointer-update
  [{:keys [page-id profile-id session-id x y] :as msg}]
  (ptk/reify ::handle-pointer-update
    ptk/UpdateEvent
    (update [_ state]
      (let [profile  (get-in state [:workspace-users profile-id])]
        (update-in state [:workspace-presence session-id]
                   (fn [session]
                     (assoc session
                            :point (gpt/point x y)
                            :updated-at (dt/now)
                            :page-id page-id)))))))

(defn handle-pointer-send
  [file-id point]
  (ptk/reify ::handle-pointer-update
    ptk/EffectEvent
    (effect [_ state stream]
      (let [ws (get-in state [:ws file-id])
            sid (:session-id state)
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Persistence
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare persist-changes)

(defn initialize-page-persistence
  [page-id]
  (ptk/reify ::initialize-persistence
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :current-page-id page-id))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter #(or (ptk/type? ::finalize %)
                                   (ptk/type? ::initialize-page %))
                              stream)
            notifier (->> stream
                          (rx/filter (ptk/type? ::dwc/commit-changes))
                          (rx/debounce 2000)
                          (rx/merge stoper))]
        (rx/merge
         (->> stream
              (rx/filter (ptk/type? ::dwc/commit-changes))
              (rx/map deref)
              (rx/buffer-until notifier)
              (rx/map vec)
              (rx/filter (complement empty?))
              (rx/map #(persist-changes page-id %))
              (rx/take-until (rx/delay 100 stoper)))
         (->> stream
              (rx/filter #(satisfies? dwc/IBatchedChange %))
              (rx/debounce 200)
              (rx/map (fn [_] (dwc/diff-and-commit-changes page-id)))
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
      (->> (rx/zip (rp/query :file {:id file-id})
                   (rp/query :file-users {:id file-id})
                   (rp/query :project-by-id {:project-id project-id})
                   (rp/query :pages {:file-id file-id}))
           (rx/first)
           (rx/map (fn [[file users project pages]]
                     (bundle-fetched file users project pages)))
           (rx/catch (fn [{:keys [type] :as error}]
                       (when (= :not-found type)
                         (rx/of (rt/nav :not-found)))))))))

(defn- bundle-fetched
  [file users project pages]
  (ptk/reify ::bundle-fetched
    IDeref
    (-deref [_]
      {:file file
       :users users
       :project project
       :pages pages})

    ptk/UpdateEvent
    (update [_ state]
      (let [assoc-page #(assoc-in %1 [:workspace-pages (:id %2)] %2)]
        (as-> state $$
          (assoc $$
                 :workspace-file file
                 :workspace-users (d/index-by :id users)
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

(defn add-shape
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])

            id       (uuid/next)
            shape    (geom/setup-proportions attrs)

            unames   (retrieve-used-names objects)
            name     (generate-unique-name unames (:name shape))

            frame-id (if (= :frame (:type shape))
                       uuid/zero
                       (dwc/calculate-frame-overlap objects shape))

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

        (rx/of (dwc/commit-changes [rchange] [uchange] {:commit-local? true})
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
                   (dwc/calculate-frame-overlap objects moved-obj))

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
      (let [page-id (:current-page-id state)
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

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
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
      (let [page-id (:current-page-id state)
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
    dwc/IBatchedChange
    dwc/IUpdateGroup
    (get-ids [_] [id])

    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:current-page-id state)]
        (update-in state [:workspace-data pid :objects id] merge attrs)))))

;; --- Update Page Options

(defn update-options
  [opts]
  (us/verify ::cp/options opts)
  (ptk/reify ::update-options
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:current-page-id state)]
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

;; --- Delete Selected
(defn- delete-shapes
  [ids]
  (us/assert (s/coll-of ::us/uuid) ids)
  (ptk/reify ::delete-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
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
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(def delete-selected
  "Deselect all and remove all selected shapes."
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
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
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (update-in state [:workspace-data page-id :objects id] assoc :name name)))))

;; --- Shape Vertical Ordering

(defn vertical-order-selected
  [loc]
  (us/verify ::loc loc)
  (ptk/reify ::vertical-order-selected-shpes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
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
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))


;; --- Change Shape Order (D&D Ordering)

;; TODO: pending UNDO

(defn relocate-shape
  [id ref-id index]
  (us/verify ::us/uuid id)
  (us/verify ::us/uuid ref-id)
  (us/verify number? index)

  (ptk/reify ::relocate-shape
    dwc/IUpdateGroup
    (get-ids [_] [id])

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            selected (get-in state [:workspace-local :selected])
            objects (get-in state [:workspace-data page-id :objects])
            parent-id (helpers/get-parent ref-id objects)]
        (rx/of (dwc/commit-changes [{:type :mov-objects
                                 :parent-id parent-id
                                 :index index
                                 :shapes (vec selected)}]
                               []
                               {:commit-local? true}))))))

;; --- Change Page Order (D&D Ordering)

(defn relocate-page
  [id index]
  (ptk/reify ::relocate-pages
    ptk/UpdateEvent
    (update [_ state]
      (let [pages (get-in state [:workspace-file :pages])
            [before after] (split-at index pages)
            p? (partial = id)
            pages' (d/concat []
                             (remove p? before)
                             [id]
                             (remove p? after))]
        (assoc-in state [:workspace-file :pages] pages')))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (:workspace-file state)]
        (->> (rp/mutation! :reorder-pages {:page-ids (:pages file)
                                           :file-id (:id file)})
             (rx/ignore))))))

;; --- Shape / Selection Alignment and Distribution

(declare align-object-to-frame)
(declare align-objects-list)

(defn align-objects
  [axis]
  (us/verify ::geom/align-axis axis)
  (ptk/reify :align-objects
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
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
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            selected-objs (map #(get objects %) selected)
            moved-objs (geom/distribute-space selected-objs axis objects)
            updated-objs (merge objects (d/index-by :id moved-objs))]
        (assoc-in state [:workspace-data page-id :objects] updated-objs)))))


;; --- Temportal displacement for Shape / Selection
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
    dwc/IBatchedChange
    dwc/IUpdateGroup
    (get-ids [_] [id])

    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (update-in state [:workspace-data page-id :objects id]
                   geom/resize-rect attr value)))))

;; --- Shape Proportions

(defn toggle-shape-proportion-lock
  [id]
  (ptk/reify ::toggle-shape-proportion-lock
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
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
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            shape (get-in state [:workspace-data page-id :objects id])
            current-position (gpt/point (:x shape) (:y shape))
            position (gpt/point (or (:x position) (:x shape)) (or (:y position) (:y shape)))
            displacement (gmt/translate-matrix (gpt/subtract position current-position))]
        (rx/of (transforms/set-modifiers [id] {:displacement displacement})
               (transforms/apply-modifiers [id]))))))

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
      (let [page-id (:current-page-id state)]
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
        (let [page-id (:current-page-id state)
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

            page-id (:current-page-id state)
            unames (-> (get-in state [:workspace-data page-id :objects])
                       (retrieve-used-names))

            rchanges (prepare-duplicate-changes objects unames selected delta)
            uchanges (mapv #(array-map :type :del-obj :id (:id %))
                           (reverse rchanges))

            selected (->> rchanges
                          (filter #(selected (:old-id %)))
                          (map #(get-in % [:obj :id]))
                          (into #{}))]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
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
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (select-shapes #{id}))))))))

(def remove-group
  (ptk/reify ::remove-group
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
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
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Transform

(def start-rotate transforms/start-rotate)
(def start-resize transforms/start-resize)
(def start-move-selected transforms/start-move-selected)
(def move-selected transforms/move-selected)

(def set-rotation transforms/set-rotation)
(def set-modifiers transforms/set-modifiers)
(def apply-modifiers transforms/apply-modifiers)

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
   "ctrl+z" #(st/emit! dwc/undo)
   "ctrl+shift+z" #(st/emit! dwc/redo)
   "ctrl+y" #(st/emit! dwc/redo)
   "ctrl+q" #(st/emit! dwc/reinitialize-undo)
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
   "shift+up" #(st/emit! (transforms/move-selected :up true))
   "shift+down" #(st/emit! (transforms/move-selected :down true))
   "shift+right" #(st/emit! (transforms/move-selected :right true))
   "shift+left" #(st/emit! (transforms/move-selected :left true))
   "up" #(st/emit! (transforms/move-selected :up false))
   "down" #(st/emit! (transforms/move-selected :down false))
   "right" #(st/emit! (transforms/move-selected :right false))
   "left" #(st/emit! (transforms/move-selected :left false))})

