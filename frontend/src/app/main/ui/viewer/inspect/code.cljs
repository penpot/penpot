;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.code
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape-tree :as ctst]
   [app.config :as cfg]
   [app.main.data.events :as ev]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.code-block :refer [code-block]]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.icons :as i]
   [app.main.ui.shapes.text.fontfaces :refer [shapes->fonts]]
   [app.util.code-beautify :as cb]
   [app.util.code-gen :as cg]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def embed-images? true)
(def remove-localhost? true)

(def page-template
  "<!DOCTYPE html>
<html>
  <head>
    <style>
    %s
    </style>
  </head>
  <body>
  %s
  </body>
</html>")

(defn get-flex-elements [page-id shapes from]
  (let [ids (mapv :id shapes)
        ids (hooks/use-equal-memo ids)

        get-layout-children-refs
        (mf/use-memo
         (mf/deps ids page-id from)
         #(if (= from :workspace)
            (refs/workspace-get-flex-child ids)
            (refs/get-flex-child-viewer ids page-id)))]

    (mf/deref get-layout-children-refs)))

(defn get-objects [from]
  (let [page-objects-ref
        (mf/use-memo
         (mf/deps from)
         (fn []
           (if (= from :workspace)
             refs/workspace-page-objects
             (refs/get-viewer-objects))))]
    (mf/deref page-objects-ref)))

(defn shapes->images
  [shapes]
  (->> shapes
       (keep
        (fn [shape]
          (when-let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))]
            [(:id shape) (cfg/resolve-file-media data)])))))

(defn replace-map
  [value map]
  (reduce
   (fn [value [old new]]
     (str/replace value old new))
   value map))

(defn gen-all-code
  [style-code markup-code images-data]
  (let [markup-code (cond-> markup-code
                      embed-images? (replace-map images-data))

        style-code (cond-> style-code
                     embed-images? (replace-map images-data))]
    (str/format page-template style-code markup-code)))

(mf/defc code
  [{:keys [shapes frame on-expand from]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        style-type*    (mf/use-state "css")
        markup-type*   (mf/use-state "html")
        fontfaces-css* (mf/use-state nil)
        images-data*   (mf/use-state nil)

        collapsed*     (mf/use-state #{})
        collapsed-css? (contains? @collapsed* :css)
        collapsed-markup? (contains? @collapsed* :markup)

        style-type    (deref style-type*)
        markup-type   (deref markup-type*)
        fontfaces-css (deref fontfaces-css*)
        images-data   (deref images-data*)

        shapes      (->> shapes
                         (map #(gsh/translate-to-frame % frame)))

        objects    (get-objects from)

        all-children (->> shapes
                          (map :id)
                          (cfh/selected-with-children objects)
                          (ctst/sort-z-index objects)
                          (map (d/getf objects)))


        shapes (hooks/use-equal-memo shapes)
        all-children (hooks/use-equal-memo all-children)

        fonts (-> (shapes->fonts all-children)
                  (hooks/use-equal-memo))

        images-urls (-> (shapes->images all-children)
                        (hooks/use-equal-memo))
        style-code
        (mf/use-memo
         (mf/deps fontfaces-css style-type all-children cg/generate-style-code)
         (fn []
           (dm/str
            fontfaces-css "\n"
            (-> (cg/generate-style-code objects style-type all-children)
                (cb/format-code style-type)))))

        markup-code
        (mf/use-memo
         (mf/deps markup-type shapes images-data)
         (fn []
           (-> (cg/generate-markup-code objects markup-type shapes)
               (cb/format-code markup-type))))

        on-markup-copied
        (mf/use-callback
         (mf/deps markup-type)
         (fn []
           (st/emit! (ptk/event ::ev/event
                                {::ev/name "copy-inspect-code"
                                 :type markup-type}))))

        on-style-copied
        (mf/use-callback
         (mf/deps style-type)
         (fn []
           (st/emit! (ptk/event ::ev/event
                                {::ev/name "copy-inspect-style"
                                 :type style-type}))))

        {on-markup-pointer-down :on-pointer-down
         on-markup-lost-pointer-capture :on-lost-pointer-capture
         on-markup-pointer-move :on-pointer-move
         markup-size :size}
        (use-resize-hook :code 400 100 800 :y false :bottom)

        {on-style-pointer-down :on-pointer-down
         on-style-lost-pointer-capture :on-lost-pointer-capture
         on-style-pointer-move :on-pointer-move
         style-size :size}
        (use-resize-hook :code 400 100 800 :y false :bottom)

        set-style
        (mf/use-callback
         (fn [value]
           (reset! style-type* value)))

        set-markup
        (mf/use-callback
         (fn [value]
           (reset! markup-type* value)))

        handle-copy-all-code
        (mf/use-callback
         (mf/deps style-code markup-code images-data)
         (fn []
           (wapi/write-to-clipboard (gen-all-code style-code markup-code images-data))))

        ;;handle-open-review
        ;;(mf/use-callback
        ;; (fn []
        ;;   (st/emit! (dp/open-preview-selected))))

        handle-collapse
        (mf/use-callback
         (fn [e]
           (let [panel-type (keyword (dom/get-data (dom/get-current-target e) "type"))]
             (swap! collapsed*
                    (fn [collapsed]
                      (if (contains? collapsed panel-type)
                        (disj collapsed panel-type)
                        (conj collapsed panel-type)))))))]

    (mf/use-effect
     (mf/deps fonts)
     #(->> (rx/from fonts)
           (rx/merge-map fonts/fetch-font-css)
           (rx/reduce conj [])
           (rx/subs!
            (fn [result]
              (let [css (str/join "\n" result)]
                (reset! fontfaces-css* css))))))

    (mf/use-effect
     (mf/deps images-urls)
     #(->> (rx/from images-urls)
           (rx/merge-map
            (fn [[_ uri]]
              (->> (http/fetch-data-uri uri true)
                   (rx/catch (fn [_] (rx/of (hash-map uri uri)))))))
           (rx/reduce conj {})
           (rx/subs!
            (fn [result]
              (reset! images-data* result)))))

    (if new-css-system
      [:div {:class (stl/css :element-options)}
       [:div {:class (stl/css :attributes-block)}
        [:button {:class (stl/css :download-button)
                  :on-click handle-copy-all-code}
         "Copy all code"]]

       #_[:div.attributes-block
          [:button.download-button {:on-click handle-open-review}
           "Preview"]]

       [:div {:class (stl/css :code-block)}
        [:div {:class (stl/css :code-row-lang)}
         [:button {:class (stl/css :toggle-btn)
                   :data-type "css"
                   :on-click handle-collapse}
          [:span {:class (stl/css-case
                          :collapsabled-icon true
                          :rotated collapsed-css?)}
           i/arrow-refactor]]

         [:& select {:default-value style-type
                     :class (stl/css :code-lang-select)
                     :options [{:label "CSS" :value "css"}]}]

         [:div {:class (stl/css :action-btns)}
          [:button {:class (stl/css :expand-button)
                    :on-click on-expand}
           i/code-refactor]

          [:& copy-button {:data #(replace-map style-code images-data)
                           :on-copied on-style-copied}]]]

        (when-not collapsed-css?
          [:div {:class (stl/css :code-row-display)
                 :style #js {"--code-height" (str (or style-size 400) "px")}}
           [:& code-block {:type style-type
                           :code style-code}]])

        [:div {:class (stl/css :resize-area)
               :on-pointer-down on-style-pointer-down
               :on-lost-pointer-capture on-style-lost-pointer-capture
               :on-pointer-move on-style-pointer-move}]]

       [:div {:class (stl/css :code-block)}
        [:div {:class (stl/css :code-row-lang)}
         [:button {:class (stl/css :toggle-btn)
                   :data-type "markup"
                   :on-click handle-collapse}
          [:span {:class (stl/css-case
                          :collapsabled-icon true
                          :rotated collapsed-markup?)}
           i/arrow-refactor]]
         [:& select {:default-value markup-type
                     :class (stl/css :code-lang-select)
                     :options [{:label "HTML" :value "html"}
                               {:label "SVG" :value "svg"}]
                     :on-change set-markup}]

         [:div {:class (stl/css :action-btns)}
          [:button {:class (stl/css :expand-button)
                    :on-click on-expand}
           i/code-refactor]

          [:& copy-button {:data #(replace-map markup-code images-data)
                           :on-copied on-markup-copied}]]]

        (when-not collapsed-markup?
          [:div {:class (stl/css :code-row-display)
                 :style #js {"--code-height" (str (or markup-size 400) "px")}}
           [:& code-block {:type markup-type
                           :code markup-code}]])

        [:div {:class (stl/css :resize-area)
               :on-pointer-down on-markup-pointer-down
               :on-lost-pointer-capture on-markup-lost-pointer-capture
               :on-pointer-move on-markup-pointer-move}]]]



      [:div.element-options
       [:div.attributes-block
        [:button.download-button {:on-click handle-copy-all-code}
         "Copy all code"]]

       #_[:div.attributes-block
          [:button.download-button {:on-click handle-open-review}
           "Preview"]]

       [:div.code-block
        [:div.code-row-lang
         [:& select {:default-value style-type
                     :class "custom-select"
                     :options [{:label "CSS" :value "css"}]
                     :on-change set-style}]
         [:button.expand-button
          {:on-click on-expand}
          i/full-screen]

         [:& copy-button {:data #(replace-map style-code images-data)
                          :on-copied on-style-copied}]]

        [:div.code-row-display {:style #js {"--code-height" (str (or style-size 400) "px")}}
         [:& code-block {:type style-type
                         :code style-code}]]

        [:div.resize-area {:on-pointer-down on-style-pointer-down
                           :on-lost-pointer-capture on-style-lost-pointer-capture
                           :on-pointer-move on-style-pointer-move}]]

       [:div.code-block
        [:div.code-row-lang
         [:& select {:default-value markup-type
                     :class "input-option"
                     :options [{:label "HTML" :value "html"}
                               {:label "SVG" :value "svg"}]
                     :on-change set-markup}]

         [:button.expand-button
          {:on-click on-expand}
          i/full-screen]

         [:& copy-button {:data #(replace-map markup-code images-data)
                          :on-copied on-markup-copied}]]

        [:div.code-row-display {:style #js {"--code-height" (str (or markup-size 400) "px")}}
         [:& code-block {:type markup-type
                         :code markup-code}]]

        [:div.resize-area {:on-pointer-down on-markup-pointer-down
                           :on-lost-pointer-capture on-markup-lost-pointer-capture
                           :on-pointer-move on-markup-pointer-move}]]])))
