;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.file-builder
  "A version parsing helper."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.changes :as ch]
   [app.common.pages.changes-spec :as pcs]
   [app.common.spec :as us]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.spec.alpha :as spec]
   [cuerdas.core :as str]))

(def root-frame uuid/zero)
(def conjv (fnil conj []))
(def conjs (fnil conj #{}))

(defn- commit-change
  ([file change]
   (commit-change file change nil))

  ([file change {:keys [add-container?
                        fail-on-spec?]
                 :or   {add-container? false
                        fail-on-spec? false}}]
   (let [component-id (:current-component-id file)
         change (cond-> change
                  (and add-container? (some? component-id))
                  (cond->
                   :always
                    (assoc :component-id component-id)

                    (some? (:current-frame-id file))
                    (assoc :frame-id (:current-frame-id file)))

                  (and add-container? (nil? component-id))
                  (assoc :page-id  (:current-page-id file)
                         :frame-id (:current-frame-id file)))]

     (when fail-on-spec?
       (us/verify ::pcs/change change))

     (let [valid? (us/valid? ::pcs/change change)
           explain (spec/explain-str ::pcs/change change)]
       #?(:cljs
          (when-not valid?
            (do
              (.warn js/console "Invalid shape" (clj->js change))
              (.warn js/console explain)))
          :clj
          (when-not valid?
            (do
              (prn "Invalid shape" change)
              (prn explain))))

       (cond-> file
         valid?
         (-> (update :changes conjv change)
             (update :data ch/process-changes [change] false))

         (not valid?)
         (update :errors conjv change))))))

(defn- lookup-objects
  ([file]
   (if (some? (:current-component-id file))
     (get-in file [:data :components  (:current-component-id file) :objects])
     (get-in file [:data :pages-index (:current-page-id file) :objects]))))

(defn lookup-shape [file shape-id]
  (-> (lookup-objects file)
      (get shape-id)))

(defn- commit-shape [file obj]
  (let [parent-id (-> file :parent-stack peek)
        change {:type :add-obj
                :id (:id obj)
                :ignore-touched true
                :obj obj
                :parent-id parent-id}

        fail-on-spec? (or (= :group (:type obj))
                          (= :frame (:type obj)))]

    (commit-change file change {:add-container? true :fail-on-spec? fail-on-spec?})))

(defn setup-rect-selrect [{:keys [x y width height transform] :as obj}]
  (when-not (d/num? x y width height)
    (ex/raise :type :assertion
              :code :invalid-condition
              :hint "Coords not valid for object"))

  (let [rect      (gsh/make-rect x y width height)
        center    (gsh/center-rect rect)
        selrect   (gsh/rect->selrect rect)

        points (-> (gsh/rect->points rect)
                   (gsh/transform-points center transform))]

    (-> obj
        (assoc :selrect selrect)
        (assoc :points points))))

(defn- setup-path-selrect
  [{:keys [content center transform transform-inverse] :as obj}]

  (when (or (empty? content) (nil? center))
    (ex/raise :type :assertion
              :code :invalid-condition
              :hint "Path not valid"))

  (let [transform (gmt/transform-in center transform)
        transform-inverse (gmt/transform-in center transform-inverse)

        content' (gsh/transform-content content transform-inverse)
        selrect  (gsh/content->selrect content')
        points   (-> (gsh/rect->points selrect)
                     (gsh/transform-points transform))]

    (-> obj
        (dissoc :center)
        (assoc :selrect selrect)
        (assoc :points points))))

(defn- setup-selrect
  [obj]
  (if (= (:type obj) :path)
    (setup-path-selrect obj)
    (setup-rect-selrect obj)))

(defn- generate-name
  [type data]
  (if (= type :svg-raw)
    (let [tag (get-in data [:content :tag])]
      (str "svg-" (cond (string? tag) tag
                        (keyword? tag) (d/name tag)
                        (nil? tag) "node"
                        :else (str tag))))
    (str/capital (d/name type))))

(defn- add-name
  [file name]
  (let [container-id (or (:current-component-id file)
                         (:current-page-id file))]
    (-> file
        (update-in [:unames container-id] conjs name))))

(defn- unique-name
  [name file]
  (let [container-id (or (:current-component-id file)
                         (:current-page-id file))
        unames (get-in file [:unames container-id])]
    (d/unique-name name (or unames #{}))))

(defn clear-names [file]
  (dissoc file :unames))

(defn- check-name
  "Given a tag returns its layer name"
  [data file type]

  (cond-> data
    (nil? (:name data))
    (assoc :name (generate-name type data))

    :always
    (update :name unique-name file)))

;; PUBLIC API

(defn create-file
  ([name]
   (create-file (uuid/next) name))

  ([id name]
   {:id id
    :name name
    :data (-> ctf/empty-file-data
              (assoc :id id))

    ;; We keep the changes so we can send them to the backend
    :changes []}))

(defn add-page
  [file data]

  (assert (nil? (:current-component-id file)))
  (let [page-id (or (:id data) (uuid/next))
        page (-> (ctp/make-empty-page page-id "Page 1")
                 (d/deep-merge data))]
    (-> file
        (commit-change
         {:type :add-page
          :page page})

        ;; Current page being edited
        (assoc :current-page-id page-id)

        ;; Current frame-id
        (assoc :current-frame-id root-frame)

        ;; Current parent stack we'll be nesting
        (assoc :parent-stack [root-frame])

        ;; Last object id added
        (assoc :last-id nil))))

(defn close-page [file]
  (assert (nil? (:current-component-id file)))
  (-> file
      (dissoc :current-page-id)
      (dissoc :parent-stack)
      (dissoc :last-id)
      (clear-names)))

(defn add-artboard [file data]
  (let [obj (-> (cts/make-minimal-shape :frame)
                (merge data)
                (check-name file :frame)
                (setup-selrect)
                (d/without-nils))]
    (-> file
        (commit-shape obj)
        (assoc :current-frame-id (:id obj))
        (assoc :last-id (:id obj))
        (add-name (:name obj))
        (update :parent-stack conjv (:id obj)))))

(defn close-artboard [file]
  (let [parent-id (-> file :parent-id peek)
        parent (lookup-shape file parent-id)
        current-frame-id (or (:frame-id parent)
                             (when (nil? (:current-component-id file))
                               root-frame))]
    (-> file
        (assoc :current-frame-id current-frame-id)
        (update :parent-stack pop))))

(defn add-group [file data]
  (let [frame-id (:current-frame-id file)
        selrect cts/empty-selrect
        name (:name data)
        obj (-> (cts/make-minimal-group frame-id selrect name)
                (merge data)
                (check-name file :group)
                (d/without-nils))]
    (-> file
        (commit-shape obj)
        (assoc :last-id (:id obj))
        (add-name (:name obj))
        (update :parent-stack conjv (:id obj)))))

(defn close-group [file]
  (let [group-id (-> file :parent-stack peek)
        group    (lookup-shape file group-id)
        children (->> group :shapes (mapv #(lookup-shape file %)))

        file
        (cond
          (empty? children)
          (commit-change
           file
           {:type :del-obj
            :ignore-touched true
            :id group-id}
           {:add-container? true})

          (:masked-group? group)
          (let [mask (first children)]
            (commit-change
             file
             {:type :mod-obj
              :id group-id
              :operations
              [{:type :set :attr :x :val (-> mask :selrect :x) :ignore-touched true}
               {:type :set :attr :y :val (-> mask :selrect :y) :ignore-touched true}
               {:type :set :attr :width :val (-> mask :selrect :width) :ignore-touched true}
               {:type :set :attr :height :val (-> mask :selrect :height) :ignore-touched true}
               {:type :set :attr :flip-x :val (-> mask :flip-x) :ignore-touched true}
               {:type :set :attr :flip-y :val (-> mask :flip-y) :ignore-touched true}
               {:type :set :attr :selrect :val (-> mask :selrect) :ignore-touched true}
               {:type :set :attr :points :val (-> mask :points) :ignore-touched true}]}
             {:add-container? true}))

          :else
          (let [group' (gsh/update-group-selrect group children)]
            (commit-change
             file
             {:type :mod-obj
              :id group-id
              :operations
              [{:type :set :attr :selrect :val (:selrect group') :ignore-touched true}
               {:type :set :attr :points  :val (:points group') :ignore-touched true}
               {:type :set :attr :x       :val (-> group' :selrect :x) :ignore-touched true}
               {:type :set :attr :y       :val (-> group' :selrect :y) :ignore-touched true}
               {:type :set :attr :width   :val (-> group' :selrect :width) :ignore-touched true}
               {:type :set :attr :height  :val (-> group' :selrect :height) :ignore-touched true}]}

             {:add-container? true})))]

    (-> file
        (update :parent-stack pop))))

(defn add-bool [file data]
  (let [frame-id (:current-frame-id file)
        name (:name data)
        obj (-> {:id (uuid/next)
                 :type :bool
                 :name name
                 :shapes []
                 :frame-id frame-id}
                (merge data)
                (check-name file :bool)
                (d/without-nils))]
    (-> file
        (commit-shape obj)
        (assoc :last-id (:id obj))
        (add-name (:name obj))
        (update :parent-stack conjv (:id obj)))))

(defn close-bool [file]
  (let [bool-id (-> file :parent-stack peek)
        bool    (lookup-shape file bool-id)
        children (->> bool :shapes (mapv #(lookup-shape file %)))

        file
        (cond
          (empty? children)
          (commit-change
           file
           {:type :del-obj
            :ignore-touched true
            :id bool-id}
           {:add-container? true})

          :else
          (let [objects (lookup-objects file)
                bool' (gsh/update-bool-selrect bool children objects)]
            (commit-change
             file
             {:type :mod-obj
              :id bool-id
              :operations
              [{:type :set :attr :selrect :val (:selrect bool') :ignore-touched true}
               {:type :set :attr :points  :val (:points bool') :ignore-touched true}
               {:type :set :attr :x       :val (-> bool' :selrect :x) :ignore-touched true}
               {:type :set :attr :y       :val (-> bool' :selrect :y) :ignore-touched true}
               {:type :set :attr :width   :val (-> bool' :selrect :width) :ignore-touched true}
               {:type :set :attr :height  :val (-> bool' :selrect :height) :ignore-touched true}]}

             {:add-container? true})))]

    (-> file
        (update :parent-stack pop))))

(defn create-shape [file type data]
  (let [obj (-> (cts/make-minimal-shape type)
                (merge data)
                (check-name file :type)
                (setup-selrect)
                (d/without-nils))]
    (-> file
        (commit-shape obj)
        (assoc :last-id (:id obj))
        (add-name (:name obj)))))

(defn create-rect [file data]
  (create-shape file :rect data))

(defn create-circle [file data]
  (create-shape file :circle data))

(defn create-path [file data]
  (create-shape file :path data))

(defn create-text [file data]
  (create-shape file :text data))

(defn create-image [file data]
  (create-shape file :image data))

(declare close-svg-raw)

(defn create-svg-raw [file data]
  (let [file (as-> file $
               (create-shape $ :svg-raw data)
               (update $ :parent-stack conjv (:last-id $)))

        create-child
        (fn [file child]
          (-> file
              (create-svg-raw (assoc data
                                     :id (uuid/next)
                                     :content child))
              (close-svg-raw)))]

    ;; First :content is the the shape attribute, the other content is the
    ;; XML children
    (reduce create-child file (get-in data [:content :content]))))

(defn close-svg-raw [file]
  (-> file
      (update :parent-stack pop)))

(defn- read-classifier
  [interaction-src]
  (select-keys interaction-src [:event-type :action-type]))

(defmulti read-event-opts :event-type)

(defmethod read-event-opts :after-delay
  [interaction-src]
  (select-keys interaction-src [:delay]))

(defmethod read-event-opts :default
  [_]
  {})

(defmulti read-action-opts :action-type)

(defmethod read-action-opts :navigate
  [interaction-src]
  (select-keys interaction-src [:destination
                                :preserve-scroll]))

(defmethod read-action-opts :open-overlay
  [interaction-src]
  (select-keys interaction-src [:destination
                                :overlay-position
                                :overlay-pos-type
                                :close-click-outside
                                :background-overlay]))

(defmethod read-action-opts :toggle-overlay
  [interaction-src]
  (select-keys interaction-src [:destination
                                :overlay-position
                                :overlay-pos-type
                                :close-click-outside
                                :background-overlay]))

(defmethod read-action-opts :close-overlay
  [interaction-src]
  (select-keys interaction-src [:destination]))

(defmethod read-action-opts :prev-screen
  [_]
  {})

(defmethod read-action-opts :open-url
  [interaction-src]
  (select-keys interaction-src [:url]))

(defn add-interaction
  [file from-id interaction-src]

  (let [shape (lookup-shape file from-id)]
    (if (nil? shape)
      file
      (let [{:keys [event-type action-type]} (read-classifier interaction-src)
            {:keys [delay]} (read-event-opts interaction-src)
            {:keys [destination overlay-pos-type overlay-position url
                    close-click-outside background-overlay preserve-scroll]}
            (read-action-opts interaction-src)

            interactions (-> shape
                             :interactions
                             (conjv
                              (d/without-nils {:event-type event-type
                                               :action-type action-type
                                               :delay delay
                                               :destination destination
                                               :overlay-pos-type overlay-pos-type
                                               :overlay-position overlay-position
                                               :url url
                                               :close-click-outside close-click-outside
                                               :background-overlay background-overlay
                                               :preserve-scroll preserve-scroll})))]
        (commit-change
         file
         {:type :mod-obj
          :page-id (:current-page-id file)
          :id from-id

          :operations
          [{:type :set :attr :interactions :val interactions :ignore-touched true}]})))))

(defn generate-changes
  [file]
  (:changes file))

(defn add-library-color
  [file color]
  (let [id (or (:id color) (uuid/next))]
    (-> file
        (commit-change
         {:type :add-color
          :color (assoc color :id id)})
        (assoc :last-id id))))

(defn update-library-color
  [file color]
  (let [id (uuid/uuid (:id color))]
    (-> file
        (commit-change
         {:type :mod-color
          :color (assoc color :id id)})
        (assoc :last-id (:id color)))))

(defn delete-library-color
  [file color-id]
  (let [id (uuid/uuid color-id)]
    (-> file
        (commit-change
         {:type :del-color
          :id id}))))

(defn add-library-typography
  [file typography]
  (let [id (or (:id typography) (uuid/next))]
    (-> file
        (commit-change
         {:type :add-typography
          :id id
          :typography (assoc typography :id id)})
        (assoc :last-id id))))

(defn delete-library-typography
  [file typography-id]
  (let [id (uuid/uuid typography-id)]
    (-> file
        (commit-change
         {:type :del-typography
          :id id}))))

(defn add-library-media
  [file media]
  (let [id (or (:id media) (uuid/next))]
    (-> file
        (commit-change
         {:type :add-media
          :object (assoc media :id id)})
        (assoc :last-id id))))

(defn delete-library-media
  [file media-id]
  (let [id (uuid/uuid media-id)]
    (-> file
        (commit-change
         {:type :del-media
          :id id}))))

(defn start-component
  ([file data] (start-component file data :group))
  ([file data root-type]
   (let [selrect (or (gsh/make-selrect (:x data) (:y data) (:width data) (:height data))
                     cts/empty-selrect)
         name               (:name data)
         path               (:path data)
         main-instance-id   (:main-instance-id data)
         main-instance-page (:main-instance-page data)
         obj (-> (cts/make-shape root-type selrect data)
                 (dissoc :path
                         :main-instance-id
                         :main-instance-page
                         :main-instance-x
                         :main-instance-y)
                 (check-name file root-type)
                 (d/without-nils))]
     (-> file
         (commit-change
          {:type :add-component
           :id (:id obj)
           :name name
           :path path
           :main-instance-id main-instance-id
           :main-instance-page main-instance-page
           :shapes [obj]})

         (assoc :last-id (:id obj))
         (update :parent-stack conjv (:id obj))
         (assoc :current-component-id (:id obj))
         (assoc :current-frame-id (when (= (:type obj) :frame)
                                    (:id obj)))))))

(defn finish-component
  [file]
  (let [component-id (:current-component-id file)
        component    (lookup-shape file component-id)
        children     (->> component :shapes (mapv #(lookup-shape file %)))

        file
        (cond
          (empty? children)
          (commit-change
           file
           {:type :del-component
            :id component-id
            :skip-undelete? true})

          (:masked-group? component)
          (let [mask (first children)]
            (commit-change
             file
             {:type :mod-obj
              :id component-id
              :operations
              [{:type :set :attr :x :val (-> mask :selrect :x) :ignore-touched true}
               {:type :set :attr :y :val (-> mask :selrect :y) :ignore-touched true}
               {:type :set :attr :width :val (-> mask :selrect :width) :ignore-touched true}
               {:type :set :attr :height :val (-> mask :selrect :height) :ignore-touched true}
               {:type :set :attr :flip-x :val (-> mask :flip-x) :ignore-touched true}
               {:type :set :attr :flip-y :val (-> mask :flip-y) :ignore-touched true}
               {:type :set :attr :selrect :val (-> mask :selrect) :ignore-touched true}
               {:type :set :attr :points :val (-> mask :points) :ignore-touched true}]}

             {:add-container? true}))

          (= (:type component) :group)
          (let [component' (gsh/update-group-selrect component children)]
            (commit-change
             file
             {:type :mod-obj
              :id component-id
              :operations
              [{:type :set :attr :selrect :val (:selrect component') :ignore-touched true}
               {:type :set :attr :points  :val (:points component') :ignore-touched true}
               {:type :set :attr :x      :val (-> component' :selrect :x) :ignore-touched true}
               {:type :set :attr :y      :val (-> component' :selrect :y) :ignore-touched true}
               {:type :set :attr :width  :val (-> component' :selrect :width) :ignore-touched true}
               {:type :set :attr :height :val (-> component' :selrect :height) :ignore-touched true}]}
             {:add-container? true}))

          :else file)]

    (-> file
        (dissoc :current-component-id)
        (dissoc :current-frame-id)
        (update :parent-stack pop))))

(defn finish-deleted-component
  [component-id page-id main-instance-x main-instance-y file]
  (let [file             (assoc file :current-component-id component-id)
        page             (ctpl/get-page (:data file) page-id)
        component        (ctkl/get-component (:data file) component-id)
        main-instance-id (:main-instance-id component)

        ; To obtain a deleted component, we first create the component
        ; and the main instance in the workspace, and then delete them.
        [_ shapes]
        (ctn/make-component-instance page
                                     component
                                     (:data file)
                                     (gpt/point main-instance-x
                                                main-instance-y)
                                     true
                                     {:main-instance? true
                                      :force-id main-instance-id})]
    (as-> file $
      (reduce #(commit-change %1
                              {:type :add-obj
                               :id (:id %2)
                               :page-id (:id page)
                               :parent-id (:parent-id %2)
                               :frame-id (:frame-id %2)
                               :ignore-touched true
                               :obj %2})
              $
              shapes)
      (commit-change $ {:type :del-component
                        :id component-id})
      (reduce #(commit-change %1 {:type :del-obj
                                  :page-id page-id
                                  :ignore-touched true
                                  :id (:id %2)})
              $
              shapes)
      (dissoc $ :current-component-id))))

(defn create-component-instance
  [file data]
  (let [component-id     (uuid/uuid (:component-id data))
        x                (:x data)
        y                (:y data)
        file             (assoc file :current-component-id component-id)
        page-id          (:current-page-id file)
        page             (ctpl/get-page (:data file) page-id)
        component        (ctkl/get-component (:data file) component-id)
        ;; main-instance-id (:main-instance-id component)

        components-v2    (dm/get-in file [:options :components-v2])

        [shape shapes]
        (ctn/make-component-instance page
                                     component
                                     (:id file)
                                     (gpt/point x
                                                y)
                                     components-v2
                                     #_{:main-instance? true
                                        :force-id main-instance-id})]

    (as-> file $
      (reduce #(commit-change %1
                              {:type :add-obj
                               :id (:id %2)
                               :page-id (:id page)
                               :parent-id (:parent-id %2)
                               :frame-id (:frame-id %2)
                               :ignore-touched true
                               :obj %2})
              $
              shapes)

      (assoc $ :last-id (:id shape))
      (dissoc $ :current-component-id))))

(defn delete-object
  [file id]
  (let [page-id (:current-page-id file)]
    (commit-change
     file
     {:type :del-obj
      :page-id page-id
      :ignore-touched true
      :id id})))

(defn update-object
  [file old-obj new-obj]
  (let [page-id (:current-page-id file)
        new-obj (setup-selrect new-obj)
        attrs (d/concat-set (keys old-obj) (keys new-obj))
        generate-operation
        (fn [changes attr]
          (let [old-val (get old-obj attr)
                new-val (get new-obj attr)]
            (if (= old-val new-val)
              changes
              (conj changes {:type :set :attr attr :val new-val :ignore-touched true}))))]
    (-> file
        (commit-change
         {:type :mod-obj
          :operations (reduce generate-operation [] attrs)
          :page-id page-id
          :id (:id old-obj)})
        (assoc :last-id (:id old-obj)))))

(defn get-current-page
  [file]
  (let [page-id (:current-page-id file)]
    (-> file (get-in [:data :pages-index page-id]))))

(defn add-guide
  [file guide]

  (let [guide (cond-> guide
                (nil? (:id guide))
                (assoc :id (uuid/next)))
        page-id (:current-page-id file)
        old-guides (or (get-in file [:data :pages-index page-id :options :guides]) {})
        new-guides (assoc old-guides (:id guide) guide)]
    (-> file
        (commit-change
         {:type :set-option
          :page-id page-id
          :option :guides
          :value new-guides})
        (assoc :last-id (:id guide)))))

(defn delete-guide
  [file id]

  (let [page-id (:current-page-id file)
        old-guides (or (get-in file [:data :pages-index page-id :options :guides]) {})
        new-guides (dissoc old-guides id)]
    (-> file
        (commit-change
         {:type :set-option
          :page-id page-id
          :option :guides
          :value new-guides}))))

(defn update-guide
  [file guide]

  (let [page-id (:current-page-id file)
        old-guides (or (get-in file [:data :pages-index page-id :options :guides]) {})
        new-guides (assoc old-guides (:id guide) guide)]
    (-> file
        (commit-change
         {:type :set-option
          :page-id page-id
          :option :guides
          :value new-guides}))))
