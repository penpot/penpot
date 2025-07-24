;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.builder
  "Internal implementation of file builder. Mainly used as base impl
  for penpot library"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.changes :as ch]
   ;; [app.common.features :as cfeat]
   [app.common.files.helpers :as cph]
   [app.common.files.migrations :as fmig]
   [app.common.geom.shapes :as gsh]
   [app.common.schema :as sm]
   [app.common.svg :as csvg]
   [app.common.time :as dt]
   [app.common.types.color :as types.color]
   [app.common.types.component :as types.comp]
   [app.common.types.file :as types.file]
   [app.common.types.page :as types.page]
   [app.common.types.path :as types.path]
   [app.common.types.shape :as types.shape]
   [app.common.types.typography :as types.typography]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL & HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private root-id uuid/zero)
(def ^:private conjv (fnil conj []))
(def ^:private conjs (fnil conj #{}))

(defn- default-uuid
  [v]
  (or v (uuid/next)))

(defn- track-used-name
  [state name]
  (let [container-id (::current-page-id state)]
    (update-in state [::unames container-id] conjs name)))

(defn- commit-change
  [state change & {:keys [add-container]}]
  (let [file-id (get state ::current-file-id)]
    (assert (uuid? file-id) "no current file id")

    (let [change (cond-> change
                   add-container
                   (assoc :page-id  (::current-page-id state)
                          :frame-id (::current-frame-id state)))]
      (update-in state [::files file-id :data] ch/process-changes [change] false))))

(defn- commit-shape
  [state shape]
  (let [parent-id
        (-> state ::parent-stack peek)

        frame-id
        (get state ::current-frame-id)

        page-id
        (get state ::current-page-id)

        change
        {:type :add-obj
         :id (:id shape)
         :ignore-touched true
         :obj shape
         :parent-id parent-id
         :frame-id frame-id
         :page-id page-id}]

    (-> state
        (commit-change change)
        (track-used-name (:name shape)))))

(defn- unique-name
  [name state]
  (let [container-id (::current-page-id state)
        unames       (dm/get-in state [:unames container-id])]
    (d/unique-name name (or unames #{}))))

(defn- clear-names [file]
  (dissoc file ::unames))

(defn- assign-shape-name
  "Given a tag returns its layer name"
  [shape state]
  (cond-> shape
    (nil? (:name shape))
    (assoc :name (let [type (get shape :type)]
                   (case type
                     :frame "Board"
                     (str/capital (d/name type)))))

    :always
    (update :name unique-name state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def decode-file
  (sm/decode-fn types.file/schema:file sm/json-transformer))

(def decode-page
  (sm/decode-fn types.page/schema:page sm/json-transformer))

(def decode-shape
  (sm/decode-fn types.shape/schema:shape-attrs sm/json-transformer))

(def decode-library-color
  (sm/decode-fn types.color/schema:library-color sm/json-transformer))

(def decode-library-typography
  (sm/decode-fn types.typography/schema:typography sm/json-transformer))

(def schema:add-component
  [:map
   [:component-id ::sm/uuid]
   [:file-id {:optional true} ::sm/uuid]
   [:name {:optional true} ::sm/text]
   [:path {:optional true} ::sm/text]
   [:frame-id {:optional true} ::sm/uuid]
   [:page-id {:optional true} ::sm/uuid]])

(def ^:private check-add-component
  (sm/check-fn schema:add-component
               :hint "invalid arguments passed for add-component"))

(def decode-add-component
  (sm/decode-fn schema:add-component sm/json-transformer))

(def schema:add-bool
  [:map
   [:group-id ::sm/uuid]
   [:type [::sm/one-of types.shape/bool-types]]])

(def decode-add-bool
  (sm/decode-fn schema:add-bool sm/json-transformer))

(def ^:private check-add-bool
  (sm/check-fn schema:add-bool))

(def schema:add-file-media
  [:map
   [:id {:optional true} ::sm/uuid]
   [:name ::sm/text]
   [:width ::sm/int]
   [:height ::sm/int]])

(def decode-add-file-media
  (sm/decode-fn schema:add-file-media sm/json-transformer))

(def check-add-file-media
  (sm/check-fn schema:add-file-media))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-state
  []
  {})

(defn get-current-page
  [state]
  (let [file-id (get state ::current-file-id)
        page-id (get state ::current-page-id)]

    (assert (uuid? file-id) "expected current-file-id to be assigned")
    (assert (uuid? page-id) "expected current-page-id to be assigned")
    (dm/get-in state [::files file-id :data :pages-index page-id])))

(defn get-current-objects
  [state]
  (-> (get-current-page state)
      (get :objects)))

(defn get-shape
  [state shape-id]
  (-> (get-current-objects state)
      (get shape-id)))

;; WORKAROUND: A copy of features from staging for make the library
;; generate files compatible with version released right now. This
;; should be removed and replaced with cfeat/default-features when 2.8
;; version is released

(def default-features
  #{"fdata/shape-data-type"
    "styles/v2"
    "layout/grid"
    "components/v2"
    "plugins/runtime"
    "design-tokens/v1"})

;; WORKAROUND: the same as features
(def available-migrations
  (-> fmig/available-migrations
      (disj "003-convert-path-content")
      (disj "0002-clean-shape-interactions")
      (disj "0003-fix-root-shape")))

(defn add-file
  [state params]
  (let [params (-> params
                   (assoc :features default-features)
                   (assoc :migrations available-migrations)
                   (update :id default-uuid))
        file   (types.file/make-file params :create-page false)]
    (-> state
        (update ::files assoc (:id file) file)
        (assoc ::current-file-id (:id file)))))

(declare close-page)

(defn close-file
  [state]
  (let [state (-> state
                  (close-page)
                  (dissoc ::current-file-id))]
    state))

(defn add-page
  [state params]
  (let [page   (-> (types.page/make-empty-page params)
                   (types.page/check-page))
        change {:type :add-page
                :page page}]

    (-> state
        (commit-change change)

        ;; Current page being edited
        (assoc ::current-page-id (:id page))

        ;; Current frame-id
        (assoc ::current-frame-id root-id)

        ;; Current parent stack we'll be nesting
        (assoc ::parent-stack [root-id])

        ;; Last object id added
        (assoc ::last-id nil))))

(defn close-page [state]
  (-> state
      (dissoc ::current-page-id)
      (dissoc ::parent-stack)
      (dissoc ::last-id)
      (clear-names)))

(defn add-board
  [state params]
  (let [{:keys [id] :as shape}
        (-> params
            (update :id default-uuid)
            (assoc :type :frame)
            (assign-shape-name state)
            (types.shape/setup-shape)
            (types.shape/check-shape))]

    (-> state
        (commit-shape shape)
        (update ::parent-stack conjv id)
        (assoc ::current-frame-id id)
        (assoc ::last-id id))))

(defn close-board
  [state]
  (let [parent-id (-> state ::parent-stack peek)
        parent    (get-shape state parent-id)]
    (-> state
        (assoc ::current-frame-id (or (:frame-id parent) root-id))
        (update ::parent-stack pop))))

(defn add-group
  [state params]
  (let [{:keys [id] :as shape}
        (-> params
            (update :id default-uuid)
            (assoc :type :group)
            (assign-shape-name state)
            (types.shape/setup-shape)
            (types.shape/check-shape))]
    (-> state
        (commit-shape shape)
        (assoc ::last-id id)
        (update ::parent-stack conjv id))))

(defn close-group
  [state]
  (let [group-id (-> state ::parent-stack peek)
        group    (get-shape state group-id)
        children (->> (get group :shapes)
                      (into [] (keep (partial get-shape state)))
                      (not-empty))]

    (assert (some? children) "group expect to have at least 1 children")

    (let [state (if (:masked-group group)
                  (let [mask  (first children)
                        change {:type :mod-obj
                                :id group-id
                                :operations
                                [{:type :set :attr :x :val (-> mask :selrect :x) :ignore-touched true}
                                 {:type :set :attr :y :val (-> mask :selrect :y) :ignore-touched true}
                                 {:type :set :attr :width :val (-> mask :selrect :width) :ignore-touched true}
                                 {:type :set :attr :height :val (-> mask :selrect :height) :ignore-touched true}
                                 {:type :set :attr :flip-x :val (-> mask :flip-x) :ignore-touched true}
                                 {:type :set :attr :flip-y :val (-> mask :flip-y) :ignore-touched true}
                                 {:type :set :attr :selrect :val (-> mask :selrect) :ignore-touched true}
                                 {:type :set :attr :points :val (-> mask :points) :ignore-touched true}]}]
                    (commit-change state change :add-container true))
                  (let [group  (gsh/update-group-selrect group children)
                        change {:type :mod-obj
                                :id group-id
                                :operations
                                [{:type :set :attr :selrect :val (:selrect group) :ignore-touched true}
                                 {:type :set :attr :points  :val (:points group) :ignore-touched true}
                                 {:type :set :attr :x       :val (-> group :selrect :x) :ignore-touched true}
                                 {:type :set :attr :y       :val (-> group :selrect :y) :ignore-touched true}
                                 {:type :set :attr :width   :val (-> group :selrect :width) :ignore-touched true}
                                 {:type :set :attr :height  :val (-> group :selrect :height) :ignore-touched true}]}]

                    (commit-change state change :add-container true)))]
      (update state ::parent-stack pop))))

(defn- update-bool-style-properties
  [bool-shape objects]
  (let [xform
        (comp
         (map (d/getf objects))
         (remove cph/frame-shape?)
         (remove types.comp/is-variant?))

        children
        (->> (get bool-shape :shapes)
             (into [] xform)
             (not-empty))]

    (when-not children
      (ex/raise :type :validation
                :code :empty-children
                :hint "expected a group with at least one shape for creating a bool"))

    (let [head  (if (= type :difference)
                  (first children)
                  (last children))
          fills (if (and (contains? head :svg-attrs) (empty? (:fills head)))
                  (types.path/get-default-bool-fills)
                  (get head :fills))]
      (-> bool-shape
          (assoc :fills fills)
          (assoc :stroks (get head :strokes))))))

(defn add-bool
  [state params]
  (let [{:keys [group-id type]}
        (check-add-bool params)

        group
        (get-shape state group-id)

        objects
        (get-current-objects state)

        bool
        (-> group
            (assoc :type :bool)
            (assoc :bool-type type)
            (update-bool-style-properties objects)
            (types.path/update-bool-shape objects))

        selrect
        (get bool :selrect)

        operations
        [{:type :set :attr :content :val (:content bool) :ignore-touched true}
         {:type :set :attr :type :val :bool :ignore-touched true}
         {:type :set :attr :bool-type :val type :ignore-touched true}
         {:type :set :attr :selrect :val selrect :ignore-touched true}
         {:type :set :attr :points :val (:points bool) :ignore-touched true}
         {:type :set :attr :x :val (get selrect :x) :ignore-touched true}
         {:type :set :attr :y :val (get selrect :y) :ignore-touched true}
         {:type :set :attr :width :val (get selrect :width) :ignore-touched true}
         {:type :set :attr :height :val (get selrect :height) :ignore-touched true}
         {:type :set :attr :fills :val (:fills bool) :ignore-touched true}
         {:type :set :attr :strokes :val (:strokes bool) :ignore-touched true}]

        change
        {:type :mod-obj
         :id (:id bool)
         :operations operations}]

    (-> state
        (commit-change change :add-container true)
        (assoc ::last-id group-id))))

(defn add-shape
  [state params]
  (let [obj (-> params
                (d/update-when :svg-attrs csvg/attrs->props)
                (types.shape/setup-shape)
                (assign-shape-name state))]
    (-> state
        (commit-shape obj)
        (assoc ::last-id (:id obj)))))

(defn add-library-color
  [state color]
  (let [color  (-> color
                   (update :opacity d/nilv 1)
                   (update :id default-uuid)
                   (types.color/check-library-color color))

        change {:type :add-color
                :color color}]

    (-> state
        (commit-change change)
        (assoc ::last-id (:id color)))))

(defn add-library-typography
  [state typography]
  (let [typography (-> typography
                       (update :id default-uuid)
                       (d/without-nils))
        change     {:type :add-typography
                    :id (:id typography)
                    :typography typography}]
    (-> state
        (commit-change change)
        (assoc ::last-id (:id typography)))))

(defn add-component
  [state params]
  (let [{:keys [component-id file-id page-id frame-id name path]}
        (-> (check-add-component params)
            (update :component-id default-uuid))

        file-id
        (or file-id (::current-file-id state))

        page-id
        (or page-id (get state ::current-page-id))

        frame-id
        (or frame-id (get state ::current-frame-id))

        change1
        (d/without-nils
         {:type :add-component
          :id component-id
          :name (or name "anonmous")
          :path path
          :main-instance-id frame-id
          :main-instance-page page-id})

        change2
        {:type :mod-obj
         :id frame-id
         :page-id page-id
         :operations
         [{:type :set :attr :component-root :val true}
          {:type :set :attr :main-instance :val true}
          {:type :set :attr :component-id :val component-id}
          {:type :set :attr :component-file :val file-id}]}]

    (-> state
        (commit-change change1)
        (commit-change change2))))

(defn delete-shape
  [file id]
  (commit-change
   file
   {:type :del-obj
    :page-id (::current-page-id file)
    :ignore-touched true
    :id id}))

(defn update-shape
  [state shape-id f]
  (let [page-id   (get state ::current-page-id)

        objects   (get-current-objects state)
        old-shape (get objects shape-id)

        new-shape (f old-shape)
        attrs     (d/concat-set
                   (keys old-shape)
                   (keys new-shape))

        generate-operation
        (fn [changes attr]
          (let [old-val (get old-shape attr)
                new-val (get new-shape attr)]
            (if (= old-val new-val)
              changes
              (conj changes {:type :set :attr attr :val new-val :ignore-touched true}))))]

    (-> state
        (commit-change
         {:type :mod-obj
          :operations (reduce generate-operation [] attrs)
          :page-id page-id
          :id (:id old-shape)})
        (assoc ::last-id shape-id))))

(defn add-guide
  [state guide]
  (let [guide (cond-> guide
                (nil? (:id guide))
                (update :id default-uuid))
        page-id (::current-page-id state)]
    (-> state
        (commit-change
         {:type :set-guide
          :page-id page-id
          :id (:id guide)
          :params guide})
        (assoc ::last-id (:id guide)))))

(defn delete-guide
  [state id]
  (let [page-id (::current-page-id state)]
    (commit-change state
                   {:type :set-guide
                    :page-id page-id
                    :id id
                    :params nil})))

(defn update-guide
  [state guide]
  (let [page-id (::current-page-id state)]
    (commit-change state
                   {:type :set-guide
                    :page-id page-id
                    :id (:id guide)
                    :params guide})))

(defrecord BlobWrapper [mtype size blob])

(defn add-file-media
  [state params blob]
  (assert (instance? BlobWrapper blob) "expect blob to be wrapped")

  (let [media-id
        (uuid/next)

        file-id
        (get state ::current-file-id)

        {:keys [id width height name]}
        (-> params
            (update :id default-uuid)
            (check-add-file-media params))]

    (-> state
        (update ::blobs assoc media-id blob)
        (update ::media assoc media-id
                {:id media-id
                 :bucket "file-media-object"
                 :content-type (get blob :mtype)
                 :size (get blob :size)})
        (update ::file-media assoc id
                {:id id
                 :created-at (dt/now)
                 :name name
                 :width width
                 :height height
                 :file-id file-id
                 :media-id media-id
                 :is-local true
                 :mtype (get blob :mtype)})

        (assoc ::last-id id))))
