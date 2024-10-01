;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.page
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.record :as crc]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dc]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.guides :as dwgu]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.comments :as pc]
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.plugins.register :as r]
   [app.plugins.ruler-guides :as rg]
   [app.plugins.shape :as shape]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(deftype FlowProxy [$plugin $file $page $id]
  Object
  (remove [_]
    (st/emit! (dwi/remove-flow $page $id))))

(defn flow-proxy? [p]
  (instance? FlowProxy p))

(defn flow-proxy
  [plugin-id file-id page-id id]
  (crc/add-properties!
   (FlowProxy. plugin-id file-id page-id id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$file" :enumerable false :get (constantly file-id)}
   {:name "$page" :enumerable false :get (constantly page-id)}
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "page" :enumerable false :get (fn [_] (u/locate-page file-id page-id))}

   {:name "name"
    :get #(-> % u/proxy->flow :name)
    :set
    (fn [_ value]
      (cond
        (or (not (string? value)) (empty? value))
        (u/display-not-valid :name value)

        :else
        (st/emit! (dwi/update-flow page-id id #(assoc % :name value)))))}

   {:name "startingBoard"
    :get
    (fn [self]
      (let [frame (-> self u/proxy->flow :starting-frame)]
        (u/locate-shape file-id page-id frame)))
    :set
    (fn [_ value]
      (cond
        (not (shape/shape-proxy? value))
        (u/display-not-valid :startingBoard value)

        :else
        (st/emit! (dwi/update-flow page-id id #(assoc % :starting-frame (obj/get value "$id"))))))}))

(deftype PageProxy [$plugin $file $id]
  Object
  (getShapeById
    [_ shape-id]
    (cond
      (not (string? shape-id))
      (u/display-not-valid :getShapeById shape-id)

      :else
      (let [shape-id (uuid/uuid shape-id)
            shape (u/locate-shape $file $id shape-id)]
        (when (some? shape)
          (shape/shape-proxy $plugin $file $id shape-id)))))

  (getRoot
    [_]
    (shape/shape-proxy $plugin $file $id uuid/zero))

  (findShapes
    [_ criteria]
    ;; Returns a lazy (iterable) of all available shapes
    (let [criteria (parser/parse-criteria criteria)
          match-criteria?
          (if (some? criteria)
            (fn [[_ shape]]
              (and
               (or (not (:name criteria))
                   (= (str/lower (:name criteria)) (str/lower (:name shape))))

               (or (not (:name-like criteria))
                   (str/includes? (str/lower (:name shape)) (str/lower (:name-like criteria))))

               (or (not (:type criteria))
                   (= (:type criteria) (:type shape)))))
            identity)]
      (when (and (some? $file) (some? $id))
        (let [page (u/locate-page $file $id)
              xf (comp
                  (filter match-criteria?)
                  (map #(shape/shape-proxy $plugin $file $id (first %))))]
          (apply array (sequence xf (:objects page)))))))

  ;; Plugin data
  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :page-plugin-data-key key)

      :else
      (let [page (u/proxy->page self)]
        (dm/get-in page [:plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (not (string? key))
      (u/display-not-valid :setPluginData-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :setPluginData-value value)

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :setPluginData "Plugin doesn't have 'content:write' permission")

      :else
      (st/emit! (dw/set-plugin-data $file :page $id (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [page (u/proxy->page self)]
      (apply array (keys (dm/get-in page [:plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :page-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :page-plugin-data-key key)

      :else
      (let [page (u/proxy->page self)]
        (dm/get-in page [:plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]

    (cond
      (not (string? namespace))
      (u/display-not-valid :setSharedPluginData-namespace namespace)

      (not (string? key))
      (u/display-not-valid :setSharedPluginData-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :setSharedPluginData-value value)

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :setSharedPluginData "Plugin doesn't have 'content:write' permission")

      :else
      (st/emit! (dw/set-plugin-data $file :page $id (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :page-plugin-data-namespace namespace)

      :else
      (let [page (u/proxy->page self)]
        (apply array (keys (dm/get-in page [:plugin-data (keyword "shared" namespace)]))))))

  (openPage
    [_]
    (cond
      (not (r/check-permission $plugin "content:read"))
      (u/display-not-valid :openPage "Plugin doesn't have 'content:read' permission")

      :else
      (st/emit! (dw/go-to-page $id))))

  (createFlow
    [_ name frame]
    (cond
      (or (not (string? name)) (empty? name))
      (u/display-not-valid :createFlow-name name)

      (not (shape/shape-proxy? frame))
      (u/display-not-valid :createFlow-frame frame)

      :else
      (let [flow-id (uuid/next)]
        (st/emit! (dwi/add-flow flow-id $id name (obj/get frame "$id")))
        (flow-proxy $plugin $file $id flow-id))))

  (removeFlow
    [_ flow]
    (cond
      (not (flow-proxy? flow))
      (u/display-not-valid :removeFlow-flow flow)

      :else
      (st/emit! (dwi/remove-flow $id (obj/get flow "$id")))))

  (addRulerGuide
    [_ orientation value board]
    (let [shape (u/proxy->shape board)]
      (cond
        (not (us/safe-number? value))
        (u/display-not-valid :addRulerGuide "Value not a safe number")

        (not (contains? #{"vertical" "horizontal"} orientation))
        (u/display-not-valid :addRulerGuide "Orientation should be either 'vertical' or 'horizontal'")

        (and (some? shape)
             (or (not (shape/shape-proxy? board))
                 (not (cfh/frame-shape? shape))))
        (u/display-not-valid :addRulerGuide "The shape is not a board")

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :addRulerGuide "Plugin doesn't have 'content:write' permission")

        :else
        (let [id (uuid/next)]
          (st/emit!
           (dwgu/update-guides
            (d/without-nils
             {:id       id
              :axis     (parser/orientation->axis orientation)
              :position value
              :frame-id (when board (obj/get board "$id"))})))
          (rg/ruler-guide-proxy $plugin $file $id id)))))

  (removeRulerGuide
    [_ value]
    (cond
      (not (rg/ruler-guide-proxy? value))
      (u/display-not-valid :removeRulerGuide "Guide not provided")

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :removeRulerGuide "Plugin doesn't have 'comment:write' permission")

      :else
      (let [guide (u/proxy->ruler-guide value)]
        (st/emit! (dwgu/remove-guide guide)))))

  (addCommentThread
    [_ content position board]
    (let [shape (when board (u/proxy->shape board))
          position (parser/parse-point position)]
      (cond
        (or (not (string? content)) (empty? content))
        (u/display-not-valid :addCommentThread "Content not valid")

        (or (not (us/safe-number? (:x position)))
            (not (us/safe-number? (:y position))))
        (u/display-not-valid :addCommentThread "Position not valid")

        (and (some? board) (or (not (shape/shape-proxy? board)) (not (cfh/frame-shape? shape))))
        (u/display-not-valid :addCommentThread "Board not valid")

        (not (r/check-permission $plugin "comment:write"))
        (u/display-not-valid :addCommentThread "Plugin doesn't have 'comment:write' permission")

        :else
        (let [position
              (cond-> position
                (some? board)
                (->  (update :x - (:x board))
                     (update :y - (:y board))))]
          (p/create
           (fn [resolve]
             (st/emit!
              (dc/create-thread-on-workspace
               {:file-id $file
                :page-id $id
                :position (gpt/point position)
                :content content}

               (fn [data]
                 (->> (rp/cmd! :get-team-users {:file-id $file})
                      (rx/subs!
                       (fn [users]
                         (let [users (d/index-by :id users)]
                           (resolve (pc/comment-thread-proxy $plugin $file $id users data)))))))
               false))))))))

  (removeCommentThread
    [_ thread]
    (cond
      (not (pc/comment-thread-proxy? thread))
      (u/display-not-valid :removeCommentThread "Comment thread not valid")

      (not (r/check-permission $plugin "comment:write"))
      (u/display-not-valid :removeCommentThread "Plugin doesn't have 'content:write' permission")

      :else
      (p/create
       (fn [resolve]
         (let [thread-id (obj/get thread "$id")]
           (p/create
            (st/emit! (dc/delete-comment-thread-on-workspace {:id thread-id} #(resolve)))))))))

  (findCommentThreads
    [_ criteria]
    (let [only-yours    (boolean (obj/get criteria "onlyYours" false))
          show-resolved (boolean (obj/get criteria "showResolved" true))
          user-id      (-> @st/state :profile :id)]
      (p/create
       (fn [resolve reject]
         (cond
           (not (r/check-permission $plugin "comment:read"))
           (do
             (u/display-not-valid :findCommentThreads "Plugin doesn't have 'comment:read' permission")
             (reject "Plugin doesn't have 'comment:read' permission"))

           :else
           (->> (rx/zip (rp/cmd! :get-team-users {:file-id $file})
                        (rp/cmd! :get-comment-threads {:file-id $file}))
                (rx/take 1)
                (rx/subs!
                 (fn [[users comments]]
                   (let [users (d/index-by :id users)
                         comments
                         (cond->> comments
                           (not show-resolved)
                           (filter (comp not :is-resolved))

                           only-yours
                           (filter #(contains? (:participants %) user-id)))]
                     (resolve
                      (format/format-array
                       #(pc/comment-thread-proxy $plugin $file $id users %) comments))))
                 reject))))))))

(crc/define-properties!
  PageProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "PageProxy"))})

(defn page-proxy? [p]
  (instance? PageProxy p))

(defn page-proxy
  [plugin-id file-id id]
  (crc/add-properties!
   (PageProxy. plugin-id file-id id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "$file" :enumerable false :get (constantly file-id)}

   {:name "id"
    :get #(dm/str (obj/get % "$id"))}

   {:name "name"
    :get #(-> % u/proxy->page :name)
    :set
    (fn [_ value]
      (cond
        (not (string? value))
        (u/display-not-valid :name value)

        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :name "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dw/rename-page id value))))}

   {:name "root"
    :enumerable false
    :get #(.getRoot ^js %)}

   {:name "background"
    :enumerable false
    :get #(or (-> % u/proxy->page :background) cc/canvas)
    :set
    (fn [_ value]
      (cond
        (or (not (string? value)) (not (cc/valid-hex-color? value)))
        (u/display-not-valid :background value)

        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :background "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dw/change-canvas-color id {:color value}))))}

   {:name "flows"
    :get
    (fn [self]
      (let [flows (d/nilv (-> (u/proxy->page self) :flows) [])]
        (format/format-array #(flow-proxy plugin-id file-id id (:id %)) flows)))}

   {:name "rulerGuides"
    :get
    (fn [self]
      (let [guides (-> (u/proxy->page self) :guides)]
        (->> guides
             (vals)
             (filter #(nil? (:frame-id %)))
             (format/format-array #(rg/ruler-guide-proxy plugin-id file-id id (:id %))))))}))
