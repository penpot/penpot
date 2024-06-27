;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.api
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.record :as cr]
   [app.common.schema :as sm]
   [app.common.text :as txt]
   [app.common.types.color :as ctc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as ch]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.media :as dwm]
   [app.main.store :as st]
   [app.plugins.events :as events]
   [app.plugins.file :as file]
   [app.plugins.fonts :as fonts]
   [app.plugins.format :as format]
   [app.plugins.library :as library]
   [app.plugins.page :as page]
   [app.plugins.parser :as parser]
   [app.plugins.shape :as shape]
   [app.plugins.user :as user]
   [app.plugins.utils :as u]
   [app.plugins.viewport :as viewport]
   [app.util.code-gen :as cg]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

;;
;; PLUGINS PUBLIC API - The plugins will able to access this functions
;;
(defn create-shape
  [plugin-id type]
  (let [page-id (:current-page-id @st/state)
        page (dm/get-in @st/state [:workspace-data :pages-index page-id])
        shape (cts/setup-shape {:type type
                                :x 0 :y 0 :width 100 :height 100})
        changes
        (-> (cb/empty-changes)
            (cb/with-page page)
            (cb/with-objects (:objects page))
            (cb/add-object shape))]
    (st/emit! (ch/commit-changes changes))
    (shape/shape-proxy plugin-id (:id shape))))

(deftype PenpotContext [$plugin]
  Object
  (addListener
    [_ type callback]
    (events/add-listener type $plugin callback))

  (removeListener
    [_ listener-id]
    (events/remove-listener listener-id))

  (getViewport
    [_]
    (viewport/viewport-proxy $plugin))

  (getFile
    [_]
    (file/file-proxy $plugin (:current-file-id @st/state)))

  (getPage
    [_]
    (let [file-id (:current-file-id @st/state)
          page-id (:current-page-id @st/state)]
      (page/page-proxy $plugin file-id page-id)))

  (getSelected
    [_]
    (let [selection (get-in @st/state [:workspace-local :selected])]
      (apply array (map str selection))))

  (getSelectedShapes
    [_]
    (let [selection (get-in @st/state [:workspace-local :selected])]
      (apply array (sequence (map (partial shape/shape-proxy $plugin)) selection))))

  (shapesColors
    [_ shapes]
    (cond
      (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
      (u/display-not-valid :shapesColors-shapes shapes)

      :else
      (let [objects (u/locate-objects)
            shapes (->> shapes
                        (map #(obj/get % "$id"))
                        (mapcat #(cfh/get-children-with-self objects %)))
            file-id (:current-file-id @st/state)
            shared-libs (:workspace-libraries @st/state)]

        (->> (ctc/extract-all-colors shapes file-id shared-libs)
             (group-by :attrs)
             (format/format-array format/format-color-result)))))

  (replaceColor
    [_ shapes old-color new-color]

    (let [old-color (parser/parse-color old-color)
          new-color (parser/parse-color new-color)]
      (cond
        (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
        (u/display-not-valid :replaceColor-shapes shapes)

        (not (sm/validate ::ctc/color old-color))
        (u/display-not-valid :replaceColor-oldColor old-color)

        (not (sm/validate ::ctc/color new-color))
        (u/display-not-valid :replaceColor-newColor new-color)

        :else
        (let [file-id (:current-file-id @st/state)
              shared-libs (:workspace-libraries @st/state)
              objects (u/locate-objects)
              shapes
              (->> shapes
                   (map #(obj/get % "$id"))
                   (mapcat #(cfh/get-children-with-self objects %)))

              shapes-by-color
              (->> (ctc/extract-all-colors shapes file-id shared-libs)
                   (group-by :attrs))]
          (st/emit! (dwc/change-color-in-selected new-color (get shapes-by-color old-color) old-color))))))

  (getRoot
    [_]
    (shape/shape-proxy $plugin uuid/zero))

  (getTheme
    [_]
    (let [theme (get-in @st/state [:profile :theme])]
      (if (or (not theme) (= theme "default"))
        "dark"
        (get-in @st/state [:profile :theme]))))

  (getCurrentUser
    [_]
    (user/current-user-proxy $plugin (:session-id @st/state)))

  (getActiveUsers
    [_]
    (apply array
           (->> (:workspace-presence @st/state)
                (vals)
                (remove #(= (:id %) (:session-id @st/state)))
                (map #(user/active-user-proxy $plugin (:id %))))))

  (uploadMediaUrl
    [_ name url]
    (cond
      (not (string? name))
      (u/display-not-valid :uploadMedia-name name)

      (not (string? url))
      (u/display-not-valid :uploadMedia-url url)

      :else
      (let [file-id (:current-file-id @st/state)]
        (p/create
         (fn [resolve reject]
           (->> (dwm/upload-media-url name file-id url)
                (rx/take 1)
                (rx/map format/format-image)
                (rx/subs! resolve reject)))))))

  (uploadMediaData
    [_ name data mime-type]
    (let [file-id (:current-file-id @st/state)]
      (p/create
       (fn [resolve reject]
         (->> (dwm/process-blobs
               {:file-id file-id
                :local? false
                :name name
                :blobs [(js/Blob. #js [data] #js {:type mime-type})]
                :on-image identity
                :on-svg identity})
              (rx/take 1)
              (rx/map format/format-image)
              (rx/subs! resolve reject))))))

  (group
    [_ shapes]
    (cond
      (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
      (u/display-not-valid :group-shapes shapes)

      :else
      (let [file-id (:current-file-id @st/state)
            page-id (:current-page-id @st/state)
            id (uuid/next)
            ids (into #{} (map #(obj/get % "$id")) shapes)]
        (st/emit! (dwg/group-shapes id ids))
        (shape/shape-proxy $plugin file-id page-id id))))

  (ungroup
    [_ group & rest]

    (cond
      (not (shape/shape-proxy? group))
      (u/display-not-valid :ungroup group)

      (and (some? rest) (not (every? shape/shape-proxy? rest)))
      (u/display-not-valid :ungroup rest)

      :else
      (let [shapes (concat [group] rest)
            ids (into #{} (map #(obj/get % "$id")) shapes)]
        (st/emit! (dwg/ungroup-shapes ids)))))

  (createFrame
    [_]
    (create-shape $plugin :frame))

  (createRectangle
    [_]
    (create-shape $plugin :rect))

  (createEllipse
    [_]
    (create-shape $plugin :circle))

  (createPath
    [_]
    (let [page-id (:current-page-id @st/state)
          page (dm/get-in @st/state [:workspace-data :pages-index page-id])
          shape (cts/setup-shape
                 {:type :path
                  :content [{:command :move-to :params {:x 0 :y 0}}
                            {:command :line-to :params {:x 100 :y 100}}]})
          changes
          (-> (cb/empty-changes)
              (cb/with-page page)
              (cb/with-objects (:objects page))
              (cb/add-object shape))]
      (st/emit! (ch/commit-changes changes))
      (shape/shape-proxy $plugin (:id shape))))

  (createText
    [_ text]
    (cond
      (or (not (string? text)) (empty? text))
      (u/display-not-valid :createText text)

      :else
      (let [file-id (:current-file-id @st/state)
            page-id (:current-page-id @st/state)
            page (dm/get-in @st/state [:workspace-data :pages-index page-id])
            shape (-> (cts/setup-shape {:type :text :x 0 :y 0 :grow-type :auto-width})
                      (txt/change-text text)
                      (assoc :position-data nil))
            changes
            (-> (cb/empty-changes)
                (cb/with-page page)
                (cb/with-objects (:objects page))
                (cb/add-object shape))]
        (st/emit! (ch/commit-changes changes))
        (shape/shape-proxy $plugin file-id page-id (:id shape)))))

  (createShapeFromSvg
    [_ svg-string]
    (cond
      (or (not (string? svg-string)) (empty? svg-string))
      (u/display-not-valid :createShapeFromSvg svg-string)

      :else
      (let [id (uuid/next)
            file-id (:current-file-id @st/state)
            page-id (:current-page-id @st/state)]
        (st/emit! (dwm/create-svg-shape id "svg" svg-string (gpt/point 0 0)))
        (shape/shape-proxy $plugin file-id page-id id))))

  (createBoolean [_ bool-type shapes]
    (let [bool-type (keyword bool-type)]
      (cond
        (not (contains? cts/bool-types bool-type))
        (u/display-not-valid :createBoolean-boolType bool-type)

        (or (not (array? shapes)) (empty? shapes) (not (every? shape/shape-proxy? shapes)))
        (u/display-not-valid :createBoolean-shapes shapes)

        :else
        (let [ids (into #{} (map #(obj/get % "$id")) shapes)
              id-ret (atom nil)]
          (st/emit! (dwb/create-bool bool-type ids {:id-ret id-ret}))
          (shape/shape-proxy $plugin @id-ret)))))

  (generateMarkup
    [_ shapes options]
    (let [type (d/nilv (obj/get options "type") "html")]
      (cond
        (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
        (u/display-not-valid :generateMarkup-shapes shapes)

        (and (some? type) (not (contains? #{"html" "svg"} type)))
        (u/display-not-valid :generateMarkup-type type)

        :else
        (let [objects (u/locate-objects)
              shapes (into [] (map u/proxy->shape) shapes)]
          (cg/generate-markup-code objects type shapes)))))

  (generateStyle
    [_ shapes options]
    (let [type (d/nilv (obj/get options "type") "css")
          prelude? (d/nilv (obj/get options "withPrelude") false)
          children? (d/nilv (obj/get options "includeChildren") true)]
      (cond
        (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
        (u/display-not-valid :generateStyle-shapes shapes)

        (and (some? type) (not (contains? #{"css"} type)))
        (u/display-not-valid :generateStyle-type type)

        (and (some? prelude?) (not (boolean? prelude?)))
        (u/display-not-valid :generateStyle-withPrelude prelude?)

        (and (some? children?) (not (boolean? children?)))
        (u/display-not-valid :generateStyle-includeChildren children?)

        :else
        (let [objects (u/locate-objects)
              shapes
              (->> (into #{} (map u/proxy->shape) shapes)
                   (cfh/clean-loops objects))

              shapes-with-children
              (if children?
                (->> shapes
                     (mapcat #(cfh/get-children-with-self objects (:id %))))
                shapes)]
          (cg/generate-style-code
           objects type shapes shapes-with-children {:with-prelude? prelude?}))))))

(defn create-context
  [plugin-id]
  (cr/add-properties!
   (PenpotContext. plugin-id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "root" :get #(.getRoot ^js %)}
   {:name "currentFile" :get #(.getFile ^js %)}
   {:name "currentPage" :get #(.getPage ^js %)}
   {:name "selection" :get #(.getSelectedShapes ^js %)}
   {:name "viewport" :get #(.getViewport ^js %)}
   {:name "currentUser" :get #(.getCurrentUser ^js %)}
   {:name "activeUsers" :get #(.getActiveUsers ^js %)}
   {:name "fonts" :get (fn [_] (fonts/fonts-subcontext plugin-id))}
   {:name "library" :get (fn [_] (library/library-subcontext plugin-id))}))
