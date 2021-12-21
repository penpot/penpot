;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.file-builder
  "A version parsing helper."
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.changes :as ch]
   [app.common.pages.init :as init]
   [app.common.pages.spec :as spec]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(def root-frame uuid/zero)
(def conjv (fnil conj []))
(def conjs (fnil conj #{}))

;; This flag controls if we should execute spec validation after every commit
(def verify-on-commit? true)

(defn- commit-change
  ([file change]
   (commit-change file change nil))

  ([file change {:keys [add-container?]
                 :or   {add-container? false}}]
   (let [component-id (:current-component-id file)
         change (cond-> change
                  (and add-container? (some? component-id))
                  (assoc :component-id component-id)

                  (and add-container? (nil? component-id))
                  (assoc :page-id  (:current-page-id file)
                         :frame-id (:current-frame-id file)))]

     (when verify-on-commit?
       (us/assert ::spec/change change))
     (-> file
         (update :changes conjv change)
         (update :data ch/process-changes [change] verify-on-commit?)))))

(defn- lookup-objects
  ([file]
   (if (some? (:current-component-id file))
     (get-in file [:data :components  (:current-component-id file) :objects])
     (get-in file [:data :pages-index (:current-page-id file) :objects]))))

(defn lookup-shape [file shape-id]
  (-> (lookup-objects file)
      (get shape-id)))

(defn- commit-shape [file obj]
  (let [parent-id (-> file :parent-stack peek)]
    (-> file
        (commit-change
         {:type :add-obj
          :id (:id obj)
          :obj obj
          :parent-id parent-id}

         {:add-container? true}))))

(defn setup-rect-selrect [obj]
  (let [rect      (select-keys obj [:x :y :width :height])
        center    (gsh/center-rect rect)
        transform (:transform obj (gmt/matrix))
        selrect   (gsh/rect->selrect rect)

        points (-> (gsh/rect->points rect)
                   (gsh/transform-points center transform))]

    (-> obj
        (assoc :selrect selrect)
        (assoc :points points))))

(defn- setup-path-selrect
  [obj]
  (let [content (:content obj)
        center  (:center obj)

        transform-inverse
        (->> (:transform-inverse obj (gmt/matrix))
             (gmt/transform-in center))

        transform
        (->> (:transform obj (gmt/matrix))
             (gmt/transform-in center))

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
    :data (-> init/empty-file-data
              (assoc :id id))

    ;; We keep the changes so we can send them to the backend
    :changes []}))

(defn add-page
  [file data]

  (assert (nil? (:current-component-id file)))
  (let [page-id (or (:id data) (uuid/next))
        page (-> init/empty-page-data
                 (assoc :id page-id)
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
  (assert (nil? (:current-component-id file)))
  (let [obj (-> (init/make-minimal-shape :frame)
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
  (assert (nil? (:current-component-id file)))
  (-> file
      (assoc :current-frame-id root-frame)
      (update :parent-stack pop)))

(defn add-group [file data]
  (let [frame-id (:current-frame-id file)
        selrect init/empty-selrect
        name (:name data)
        obj (-> (init/make-minimal-group frame-id selrect name)
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
            :id group-id}
           {:add-container? true})

          (:masked-group? group)
          (let [mask (first children)]
            (commit-change
             file
             {:type :mod-obj
              :id group-id
              :operations
              [{:type :set :attr :x :val (-> mask :selrect :x)}
               {:type :set :attr :y :val (-> mask :selrect :y)}
               {:type :set :attr :width :val (-> mask :selrect :width)}
               {:type :set :attr :height :val (-> mask :selrect :height)}
               {:type :set :attr :flip-x :val (-> mask :flip-x)}
               {:type :set :attr :flip-y :val (-> mask :flip-y)}
               {:type :set :attr :selrect :val (-> mask :selrect)}
               {:type :set :attr :points :val (-> mask :points)}]}
             {:add-container? true}))

          :else
          (let [group' (gsh/update-group-selrect group children)]
            (commit-change
             file
             {:type :mod-obj
              :id group-id
              :operations
              [{:type :set :attr :selrect :val (:selrect group')}
               {:type :set :attr :points  :val (:points group')}
               {:type :set :attr :x       :val (-> group' :selrect :x)}
               {:type :set :attr :y       :val (-> group' :selrect :y)}
               {:type :set :attr :width   :val (-> group' :selrect :width)}
               {:type :set :attr :height  :val (-> group' :selrect :height)}]}

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
        (let [objects (lookup-objects file)
              bool' (gsh/update-bool-selrect bool children objects)]
          (commit-change
           file
           {:type :mod-obj
            :id bool-id
            :operations
            [{:type :set :attr :selrect :val (:selrect bool')}
             {:type :set :attr :points  :val (:points bool')}
             {:type :set :attr :x       :val (-> bool' :selrect :x)}
             {:type :set :attr :y       :val (-> bool' :selrect :y)}
             {:type :set :attr :width   :val (-> bool' :selrect :width)}
             {:type :set :attr :height  :val (-> bool' :selrect :height)}]}

           {:add-container? true}))]

    (-> file
        (update :parent-stack pop))))

(defn create-shape [file type data]
  (let [obj (-> (init/make-minimal-shape type)
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

  (assert (some? (lookup-shape file from-id)) (str "Cannot locate shape with id " from-id))

  (let [{:keys [event-type action-type]} (read-classifier interaction-src)
        {:keys [delay]} (read-event-opts interaction-src)
        {:keys [destination overlay-pos-type overlay-position url
                close-click-outside background-overlay preserve-scroll]}
        (read-action-opts interaction-src)

        interactions (-> (lookup-shape file from-id)
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
      [{:type :set :attr :interactions :val interactions}]})))

(defn generate-changes
  [file]
  (:changes file))

(defn add-library-color
  [file color]

  (let [id (or (:id color) (uuid/next))]
    (-> file
        (commit-change
         {:type :add-color
          :id id
          :color (assoc color :id id)})
        (assoc :last-id id))))

(defn add-library-typography
  [file typography]
  (let [id (or (:id typography) (uuid/next))]
    (-> file
        (commit-change
         {:type :add-typography
          :id id
          :typography (assoc typography :id id)})
        (assoc :last-id id))))

(defn add-library-media
  [file media]
  (let [id (or (:id media) (uuid/next))]
    (-> file
        (commit-change
         {:type :add-media
          :object (assoc media :id id)})
        (assoc :last-id id))))

(defn start-component
  [file data]

  (let [selrect init/empty-selrect
        name (:name data)
        path (:path data)
        obj (-> (init/make-minimal-group nil selrect name)
                (merge data)
                (check-name file :group)
                (d/without-nils))]
    (-> file
        (commit-change
         {:type :add-component
          :id (:id obj)
          :name name
          :path path
          :shapes [obj]})

        (assoc :last-id (:id obj))
        (update :parent-stack conjv (:id obj))
        (assoc :current-component-id (:id obj)))))

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
            :id component-id})

          (:masked-group? component)
          (let [mask (first children)]
            (commit-change
             file
             {:type :mod-obj
              :id component-id
              :operations
              [{:type :set :attr :x :val (-> mask :selrect :x)}
               {:type :set :attr :y :val (-> mask :selrect :y)}
               {:type :set :attr :width :val (-> mask :selrect :width)}
               {:type :set :attr :height :val (-> mask :selrect :height)}
               {:type :set :attr :flip-x :val (-> mask :flip-x)}
               {:type :set :attr :flip-y :val (-> mask :flip-y)}
               {:type :set :attr :selrect :val (-> mask :selrect)}
               {:type :set :attr :points :val (-> mask :points)}]}

             {:add-container? true}))

          :else
          (let [component' (gsh/update-group-selrect component children)]
            (commit-change
             file
             {:type :mod-obj
              :id component-id
              :operations
              [{:type :set :attr :selrect :val (:selrect component')}
               {:type :set :attr :points  :val (:points component')}
               {:type :set :attr :x      :val (-> component' :selrect :x)}
               {:type :set :attr :y      :val (-> component' :selrect :y)}
               {:type :set :attr :width  :val (-> component' :selrect :width)}
               {:type :set :attr :height :val (-> component' :selrect :height)}]}

             {:add-container? true})))]

    (-> file
        (dissoc :current-component-id)
        (update :parent-stack pop))))


