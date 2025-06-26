;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.api
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.text :as txt]
   [app.common.types.color :as ctc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as ch]
   [app.main.data.common :as dcm]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.selection :as dws]
   [app.main.fonts :refer [fetch-font-css]]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.shapes.text.fontfaces :refer [shapes->fonts]]
   [app.plugins.events :as events]
   [app.plugins.file :as file]
   [app.plugins.fonts :as fonts]
   [app.plugins.format :as format]
   [app.plugins.history :as history]
   [app.plugins.library :as library]
   [app.plugins.local-storage :as local-storage]
   [app.plugins.page :as page]
   [app.plugins.parser :as parser]
   [app.plugins.shape :as shape]
   [app.plugins.user :as user]
   [app.plugins.utils :as u]
   [app.plugins.viewport :as viewport]
   [app.util.code-gen :as cg]
   [app.util.object :as obj]
   [app.util.theme :as theme]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;;
;; PLUGINS PUBLIC API - The plugins will able to access this functions
;;
(defn create-shape
  [plugin-id type]
  (let [page  (dsh/lookup-page @st/state)
        shape (cts/setup-shape {:type type
                                :x 0 :y 0
                                :width 100
                                :height 100})
        changes
        (-> (cb/empty-changes)
            (cb/with-page page)
            (cb/with-objects (:objects page))
            (cb/add-object shape))]

    (st/emit! (ch/commit-changes changes))
    (shape/shape-proxy plugin-id (:id shape))))

(defn create-context
  [plugin-id]
  (obj/reify {:name "PenpotContext"}
    ;; Private properties
    :$plugin {:enumerable false :get (fn [] plugin-id)}

    ;; Public properties
    :root
    {:this true
     :get #(.getRoot ^js %)}

    :currentFile
    {:this true
     :get #(.getFile ^js %)}

    :currentPage
    {:this true
     :get #(.getPage ^js %)}

    :theme
    {:this true
     :get #(.getTheme ^js %)}

    :localStorage
    {:this true
     :get
     (fn [_] (local-storage/local-storage-proxy plugin-id))}

    :selection
    {:this true
     :get #(.getSelectedShapes ^js %)
     :set
     (fn [_ shapes]
       (cond
         (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
         (u/display-not-valid :selection shapes)

         :else
         (let [ids (into (d/ordered-set) (map #(obj/get % "$id")) shapes)]
           (st/emit! (dws/select-shapes ids)))))}

    :viewport
    {:this true
     :get #(.getViewport ^js %)}

    :currentUser
    {:this true
     :get #(.getCurrentUser ^js %)}

    :activeUsers
    {:this true
     :get #(.getActiveUsers ^js %)}

    :fonts
    {:get (fn [] (fonts/fonts-subcontext plugin-id))}

    :library
    {:get (fn [] (library/library-subcontext plugin-id))}

    :history
    {:get (fn [] (history/history-subcontext plugin-id))}

    ;; Methods

    :addListener
    (fn [type callback props]
      (events/add-listener type plugin-id callback props))

    :removeListener
    (fn [listener-id]
      (events/remove-listener listener-id))

    :getViewport
    (fn []
      (viewport/viewport-proxy plugin-id))

    :getFile
    (fn []
      (when (some? (:current-file-id @st/state))
        (file/file-proxy plugin-id (:current-file-id @st/state))))

    :getPage
    (fn []
      (let [file-id (:current-file-id @st/state)
            page-id (:current-page-id @st/state)]
        (when (and (some? file-id) (some? page-id))
          (page/page-proxy plugin-id file-id page-id))))

    :getSelectedShapes
    (fn []
      (let [selection (get-in @st/state [:workspace-local :selected])]
        (apply array (sequence (map (partial shape/shape-proxy plugin-id)) selection))))

    :shapesColors
    (fn [shapes]
      (cond
        (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
        (u/display-not-valid :shapesColors-shapes shapes)

        :else
        (let [objects (u/locate-objects)
              shapes (->> shapes
                          (map #(obj/get % "$id"))
                          (mapcat #(cfh/get-children-with-self objects %)))
              file-id (:current-file-id @st/state)
              shared-libs (:files @st/state)]

          (->> (ctc/extract-all-colors shapes file-id shared-libs)
               (group-by :attrs)
               (format/format-array format/format-color-result)))))

    :replaceColor
    (fn  [shapes old-color new-color]
      (let [old-color (parser/parse-color-data old-color)
            new-color (parser/parse-color-data new-color)]
        (cond
          (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
          (u/display-not-valid :replaceColor-shapes shapes)

          (not (sm/validate ctc/schema:color old-color))
          (u/display-not-valid :replaceColor-oldColor old-color)

          (not (sm/validate ctc/schema:color new-color))
          (u/display-not-valid :replaceColor-newColor new-color)

          :else
          (let [file-id (:current-file-id @st/state)
                shared-libs (:files @st/state)
                objects (u/locate-objects)
                shapes
                (->> shapes
                     (map #(obj/get % "$id"))
                     (mapcat #(cfh/get-children-with-self objects %)))

                shapes-by-color
                (->> (ctc/extract-all-colors shapes file-id shared-libs)
                     (group-by :attrs))]

            (when-let [operations (get shapes-by-color old-color)]
              (st/emit! (dwc/change-color-in-selected operations new-color old-color)))))))

    :getRoot
    (fn []
      (when (and (some? (:current-file-id @st/state))
                 (some? (:current-page-id @st/state)))
        (shape/shape-proxy plugin-id uuid/zero)))

    :getTheme
    (fn []
      (let [theme (get-in @st/state [:profile :theme])]
        (cond
          (or (not theme) (= theme "system"))
          (theme/get-system-theme)

          (= theme "default")
          "dark"

          :else
          theme)))

    :getCurrentUser
    (fn []
      (user/current-user-proxy plugin-id (:session-id @st/state)))

    :getActiveUsers
    (fn []
      (apply array
             (->> (:workspace-presence @st/state)
                  (vals)
                  (remove #(= (:id %) (:session-id @st/state)))
                  (map #(user/active-user-proxy plugin-id (:id %))))))

    :uploadMediaUrl
    (fn  [name url]
      (cond
        (not (string? name))
        (u/display-not-valid :uploadMedia-name name)

        (not (string? url))
        (u/display-not-valid :uploadMedia-url url)

        :else
        (let [file-id (:current-file-id @st/state)]
          (js/Promise.
           (fn [resolve reject]
             (->> (dwm/upload-media-url name file-id url)
                  (rx/take 1)
                  (rx/map format/format-image)
                  (rx/subs! resolve reject)))))))

    :uploadMediaData
    (fn [name data mime-type]
      (let [file-id (:current-file-id @st/state)]
        (js/Promise.
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

    :group
    (fn [shapes]
      (cond
        (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
        (u/display-not-valid :group-shapes shapes)

        :else
        (let [file-id (:current-file-id @st/state)
              page-id (:current-page-id @st/state)
              id (uuid/next)
              ids (into #{} (map #(obj/get % "$id")) shapes)]
          (st/emit! (dwg/group-shapes id ids))
          (shape/shape-proxy plugin-id file-id page-id id))))

    :ungroup
    (fn [group & rest]
      (cond
        (not (shape/shape-proxy? group))
        (u/display-not-valid :ungroup group)

        (and (some? rest) (not (every? shape/shape-proxy? rest)))
        (u/display-not-valid :ungroup rest)

        :else
        (let [shapes (concat [group] rest)
              ids (into #{} (map #(obj/get % "$id")) shapes)]
          (st/emit! (dwg/ungroup-shapes ids)))))

    :createBoard
    (fn []
      (create-shape plugin-id :frame))

    :createRectangle
    (fn []
      (create-shape plugin-id :rect))

    :createEllipse
    (fn []
      (create-shape plugin-id :circle))

    :createPath
    (fn []
      (let [page  (dsh/lookup-page @st/state)
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
        (shape/shape-proxy plugin-id (:id shape))))

    :createText
    (fn [text]
      (cond
        (or (not (string? text)) (empty? text))
        (u/display-not-valid :createText text)

        :else
        (let [page  (dsh/lookup-page @st/state)
              shape (-> (cts/setup-shape {:type :text :x 0 :y 0 :grow-type :auto-width})
                        (txt/change-text text)
                        (assoc :position-data nil))
              changes
              (-> (cb/empty-changes)
                  (cb/with-page page)
                  (cb/with-objects (:objects page))
                  (cb/add-object shape))]

          (st/emit! (ch/commit-changes changes))
          (shape/shape-proxy plugin-id (:id shape)))))

    :createShapeFromSvg
    (fn [svg-string]
      (cond
        (or (not (string? svg-string)) (empty? svg-string))
        (u/display-not-valid :createShapeFromSvg svg-string)

        :else
        (let [id (uuid/next)
              file-id (:current-file-id @st/state)
              page-id (:current-page-id @st/state)]
          (st/emit! (dwm/create-svg-shape id "svg" svg-string (gpt/point 0 0)))
          (shape/shape-proxy plugin-id file-id page-id id))))

    :createShapeFromSvgWithImages
    (fn [svg-string]
      (js/Promise.
       (fn [resolve reject]
         (cond
           (or (not (string? svg-string)) (empty? svg-string))
           (do
             (u/display-not-valid :createShapeFromSvg "Svg not valid")
             (reject "Svg not valid"))

           :else
           (let [id (uuid/next)
                 file-id (:current-file-id @st/state)
                 page-id (:current-page-id @st/state)]
             (st/emit! (dwm/create-svg-shape-with-images
                        file-id id "svg" svg-string (gpt/point 0 0)
                        #(resolve (shape/shape-proxy plugin-id file-id page-id id))
                        reject)))))))

    :createBoolean
    (fn [bool-type shapes]
      (let [bool-type (keyword bool-type)]
        (cond
          (not (contains? cts/bool-types bool-type))
          (u/display-not-valid :createBoolean-boolType bool-type)

          (or (not (array? shapes)) (empty? shapes) (not (every? shape/shape-proxy? shapes)))
          (u/display-not-valid :createBoolean-shapes shapes)

          :else
          (let [ids      (into #{} (map #(obj/get % "$id")) shapes)
                shape-id (uuid/next)]
            (st/emit! (dwb/create-bool bool-type :ids ids :force-shape-id shape-id))
            (shape/shape-proxy plugin-id shape-id)))))

    :generateMarkup
    (fn [shapes options]
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

    :generateStyle
    (fn [shapes options]
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
             objects type shapes shapes-with-children {:with-prelude? prelude?})))))

    :generateFontFaces
    (fn [shapes]
      (js/Promise.
       (fn [resolve reject]
         (let [objects (u/locate-objects)
               all-children
               (->> shapes
                    (map #(obj/get % "$id"))
                    (cfh/selected-with-children objects)
                    (map (d/getf objects)))
               fonts (shapes->fonts all-children)]
           (->> (rx/from fonts)
                (rx/merge-map fetch-font-css)
                (rx/reduce conj [])
                (rx/map #(str/join "\n" %))
                (rx/first)
                (rx/subs! #(resolve %) reject))))))

    :openViewer
    (fn []
      (let [params {:page-id (:current-page-id @st/state)
                    :file-id (:current-file-id @st/state)
                    :section "interactions"}]
        (st/emit! (dcm/go-to-viewer params))))

    :createPage
    (fn []
      (let [file-id (:current-file-id @st/state)
            id (uuid/next)]
        (st/emit! (dw/create-page {:page-id id :file-id file-id}))
        (page/page-proxy plugin-id file-id id)))
    :openPage
    (fn [page]
      (let [id (obj/get page "$id")]
        (st/emit! (dcm/go-to-workspace :page-id id ::rt/new-window true))))

    :alignHorizontal
    (fn [shapes direction]
      (let [dir (case direction
                  "left"   :hleft
                  "center" :hcenter
                  "right"  :hright
                  nil)]
        (cond
          (nil? dir)
          (u/display-not-valid :alignHorizontal-direction "Direction not valid")

          (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
          (u/display-not-valid :alignHorizontal-shapes "Not valid shapes")

          :else
          (let [ids (into #{} (map #(obj/get % "$id")) shapes)]
            (st/emit! (dw/align-objects dir ids))))))

    :alignVertical
    (fn [shapes direction]
      (let [dir (case direction
                  "top"   :vtop
                  "center" :vcenter
                  "bottom"  :vbottom
                  nil)]
        (cond
          (nil? dir)
          (u/display-not-valid :alignVertical-direction "Direction not valid")

          (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
          (u/display-not-valid :alignVertical-shapes "Not valid shapes")

          :else
          (let [ids (into #{} (map #(obj/get % "$id")) shapes)]
            (st/emit! (dw/align-objects dir ids))))))

    :distributeHorizontal
    (fn [shapes]
      (cond
        (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
        (u/display-not-valid :distributeHorizontal-shapes "Not valid shapes")

        :else
        (let [ids (into #{} (map #(obj/get % "$id")) shapes)]
          (st/emit! (dw/distribute-objects :horizontal ids)))))

    :distributeVertical
    (fn [shapes]
      (cond
        (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
        (u/display-not-valid :distributeVertical-shapes "Not valid shapes")

        :else
        (let [ids (into #{} (map #(obj/get % "$id")) shapes)]
          (st/emit! (dw/distribute-objects :vertical ids)))))

    :flatten
    (fn [shapes]
      (cond
        (or (not (array? shapes)) (not (every? shape/shape-proxy? shapes)))
        (u/display-not-valid :flatten-shapes "Not valid shapes")

        :else
        (let [ids (into #{} (map #(obj/get % "$id")) shapes)]
          (st/emit! (dw/convert-selected-to-path ids)))))))
