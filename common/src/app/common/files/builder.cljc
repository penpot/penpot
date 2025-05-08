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
   [app.common.features :as cfeat]
   [app.common.files.changes :as ch]
   [app.common.files.migrations :as fmig]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.schema :as sm]
   [app.common.svg :as csvg]
   [app.common.types.color :as types.color]
   [app.common.types.component :as types.component]
   [app.common.types.components-list :as types.components-list]
   [app.common.types.container :as types.container]
   [app.common.types.file :as types.file]
   [app.common.types.page :as types.page]
   [app.common.types.pages-list :as types.pages-list]
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

(defn default-uuid
  [v]
  (or v (uuid/next)))

(defn- track-used-name
  [file name]
  (let [container-id (::current-page-id file)]
    (update-in file [::unames container-id] conjs name)))

(defn- commit-change
  [file change & {:keys [add-container]
                  :or   {add-container false}}]

  (let [change (cond-> change
                 add-container
                 (assoc :page-id  (::current-page-id file)
                        :frame-id (::current-frame-id file)))]
    (-> file
        (update ::changes conjv change)
        (update :data ch/process-changes [change] false))))

(defn- lookup-objects
  [file]
  (dm/get-in file [:data :pages-index (::current-page-id file) :objects]))

(defn- commit-shape
  [file shape]
  (let [parent-id
        (-> file ::parent-stack peek)

        frame-id
        (::current-frame-id file)

        page-id
        (::current-page-id file)

        change
        {:type :add-obj
         :id (:id shape)
         :ignore-touched true
         :obj shape
         :parent-id parent-id
         :frame-id frame-id
         :page-id page-id}]

    (-> file
        (commit-change change)
        (track-used-name (:name shape)))))

(defn- generate-name
  [type data]
  (if (= type :svg-raw)
    (let [tag (dm/get-in data [:content :tag])]
      (str "svg-" (cond (string? tag) tag
                        (keyword? tag) (d/name tag)
                        (nil? tag) "node"
                        :else (str tag))))
    (str/capital (d/name type))))

(defn- unique-name
  [name file]
  (let [container-id (::current-page-id file)
        unames (dm/get-in file [:unames container-id])]
    (d/unique-name name (or unames #{}))))

(defn- clear-names [file]
  (dissoc file ::unames))

(defn- assign-name
  "Given a tag returns its layer name"
  [data file type]

  (cond-> data
    (nil? (:name data))
    (assoc :name (generate-name type data))

    :always
    (update :name unique-name file)))

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
  (sm/decode-fn types.color/schema:color sm/json-transformer))

(def decode-library-typography
  (sm/decode-fn types.typography/schema:typography sm/json-transformer))

(def decode-component
  (sm/decode-fn types.component/schema:component sm/json-transformer))

(def schema:add-component-instance
  [:map
   [:component-id ::sm/uuid]
   [:x ::sm/safe-number]
   [:y ::sm/safe-number]])

(def check-add-component-instance
  (sm/check-fn schema:add-component-instance))

(def decode-add-component-instance
  (sm/decode-fn schema:add-component-instance sm/json-transformer))

(def schema:add-bool
  [:map
   [:group-id ::sm/uuid]
   [:type [::sm/one-of types.shape/bool-types]]])

(def decode-add-bool
  (sm/decode-fn schema:add-bool sm/json-transformer))

(def check-add-bool
  (sm/check-fn schema:add-bool))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn lookup-shape [file shape-id]
  (-> (lookup-objects file)
      (get shape-id)))

(defn get-current-page
  [file]
  (let [page-id (::current-page-id file)]
    (dm/get-in file [:data :pages-index page-id])))

(defn create-file
  [params]
  (let [params (-> params
                   (assoc :features cfeat/default-features)
                   (assoc :migrations fmig/available-migrations))]
    (types.file/make-file params :create-page false)))

(defn add-page
  [file params]
  (let [page   (-> (types.page/make-empty-page params)
                   (types.page/check-page))
        change {:type :add-page
                :page page}]

    (-> file
        (commit-change change)

        ;; Current page being edited
        (assoc ::current-page-id (:id page))

        ;; Current frame-id
        (assoc ::current-frame-id root-id)

        ;; Current parent stack we'll be nesting
        (assoc ::parent-stack [root-id])

        ;; Last object id added
        (assoc ::last-id nil))))

(defn close-page [file]
  (-> file
      (dissoc ::current-page-id)
      (dissoc ::parent-stack)
      (dissoc ::last-id)
      (clear-names)))

(defn add-artboard
  [file data]
  (let [{:keys [id] :as shape}
        (-> data
            (update :id default-uuid)
            (assoc :type :frame)
            (assign-name file :frame)
            (types.shape/setup-shape)
            (types.shape/check-shape))]

    (-> file
        (commit-shape shape)
        (update ::parent-stack conjv id)
        (assoc ::current-frame-id id)
        (assoc ::last-id id))))

(defn close-artboard
  [file]
  (let [parent-id (-> file ::parent-stack peek)
        parent    (lookup-shape file parent-id)]
    (-> file
        (assoc ::current-frame-id (or (:frame-id parent) root-id))
        (update ::parent-stack pop))))

(defn add-group
  [file params]
  (let [{:keys [id] :as shape}
        (-> params
            (update :id default-uuid)
            (assoc :type :group)
            (assign-name file :group)
            (types.shape/setup-shape)
            (types.shape/check-shape))]
    (-> file
        (commit-shape shape)
        (assoc ::last-id id)
        (update ::parent-stack conjv id))))

(defn close-group
  [file]
  (let [group-id (-> file :parent-stack peek)
        group    (lookup-shape file group-id)
        children (->> (get group :shapes)
                      (into [] (keep (partial lookup-shape file)))
                      (not-empty))]

    (assert (some? children) "group expect to have at least 1 children")

    (let [file (if (:masked-group group)
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
                   (commit-change file change :add-container true))
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

                   (commit-change file change :add-container true)))]
      (update file ::parent-stack pop))))

(defn add-bool
  [file params]
  (let [{:keys [group-id type]}
        (check-add-bool params)

        group
        (lookup-shape file group-id)

        children
        (->> (get group :shapes)
             (not-empty))]

    (assert (some? children) "expect group to have at least 1 element")

    (let [objects  (lookup-objects file)
          bool     (-> group
                       (assoc :type :bool)
                       (gsh/update-bool objects))
          change   {:type :mod-obj
                    :id (:id bool)
                    :operations
                    [{:type :set :attr :content :val (:content bool) :ignore-touched true}
                     {:type :set :attr :type :val :bool :ignore-touched true}
                     {:type :set :attr :bool-type :val type :ignore-touched true}
                     {:type :set :attr :selrect :val (:selrect bool) :ignore-touched true}
                     {:type :set :attr :points  :val (:points bool) :ignore-touched true}
                     {:type :set :attr :x       :val (-> bool :selrect :x) :ignore-touched true}
                     {:type :set :attr :y       :val (-> bool :selrect :y) :ignore-touched true}
                     {:type :set :attr :width   :val (-> bool :selrect :width) :ignore-touched true}
                     {:type :set :attr :height  :val (-> bool :selrect :height) :ignore-touched true}]}]

      (-> file
          (commit-change change :add-container true)
          (assoc ::last-id group-id)))))

(defn add-shape
  [file params]
  (let [obj (-> params
                (d/update-when :svg-attrs csvg/attrs->props)
                (types.shape/setup-shape)
                (assign-name file :type))]
    (-> file
        (commit-shape obj)
        (assoc ::last-id (:id obj)))))

(defn add-library-color
  [file color]
  (let [color  (-> color
                   (update :id default-uuid)
                   (types.color/check-library-color color))
        change {:type :add-color
                :color color}]
    (-> file
        (commit-change change)
        (assoc ::last-id (:id color)))))

(defn add-library-typography
  [file typography]
  (let [typography (-> typography
                       (update :id default-uuid)
                       (d/without-nils))
        change     {:type :add-typography
                    :id (:id typography)
                    :typography typography}]
    (-> file
        (commit-change change)
        (assoc ::last-id (:id typography)))))

(defn add-component
  [file params]
  (let [change1 {:type :add-component
                 :id (or (:id params) (uuid/next))
                 :name (:name params)
                 :path (:path params)
                 :main-instance-id (:main-instance-id params)
                 :main-instance-page (:main-instance-page params)}

        comp-id (get change1 :id)

        change2 {:type :mod-obj
                 :id (:main-instance-id params)
                 :operations
                 [{:type :set :attr :component-root :val true}
                  {:type :set :attr :component-id :val comp-id}
                  {:type :set :attr :component-file :val (:id file)}]}]
    (-> file
        (commit-change change1)
        (commit-change change2)
        (assoc ::last-id comp-id)
        (assoc ::current-frame-id comp-id))))

(defn add-component-instance
  [{:keys [id data] :as file} params]

  (let [{:keys [component-id x y]}
        (check-add-component-instance params)

        component
        (types.components-list/get-component data component-id)

        page-id
        (get file ::current-page-id)]

    (assert (uuid? page-id) "page-id is expected to be set")
    (assert (uuid? component) "component is expected to exist")

    ;; FIXME: this should be on files and not in pages-list
    (let [page (types.pages-list/get-page (:data file) page-id)
          pos  (gpt/point x y)

          [shape shapes]
          (types.container/make-component-instance page component id pos)

          file
          (reduce #(commit-change %1
                                  {:type :add-obj
                                   :id (:id %2)
                                   :page-id (:id page)
                                   :parent-id (:parent-id %2)
                                   :frame-id (:frame-id %2)
                                   :ignore-touched true
                                   :obj %2})
                  file
                  shapes)]

      (assoc file ::last-id (:id shape)))))

(defn delete-shape
  [file id]
  (commit-change
   file
   {:type :del-obj
    :page-id (::current-page-id file)
    :ignore-touched true
    :id id}))

(defn update-shape
  [file shape-id f]
  (let [page-id   (::current-page-id file)
        objects   (lookup-objects file)
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

    (-> file
        (commit-change
         {:type :mod-obj
          :operations (reduce generate-operation [] attrs)
          :page-id page-id
          :id (:id old-shape)})
        (assoc ::last-id shape-id))))

(defn add-guide
  [file guide]
  (let [guide (cond-> guide
                (nil? (:id guide))
                (assoc :id (uuid/next)))
        page-id (::current-page-id file)]
    (-> file
        (commit-change
         {:type :set-guide
          :page-id page-id
          :id (:id guide)
          :params guide})
        (assoc ::last-id (:id guide)))))

(defn delete-guide
  [file id]

  (let [page-id (::current-page-id file)]
    (commit-change file
                   {:type :set-guide
                    :page-id page-id
                    :id id
                    :params nil})))

(defn update-guide
  [file guide]
  (let [page-id (::current-page-id file)]
    (commit-change file
                   {:type :set-guide
                    :page-id page-id
                    :id (:id guide)
                    :params guide})))

(defn strip-image-extension [filename]
  (let [image-extensions-re #"(\.png)|(\.jpg)|(\.jpeg)|(\.webp)|(\.gif)|(\.svg)$"]
    (str/replace filename image-extensions-re "")))
