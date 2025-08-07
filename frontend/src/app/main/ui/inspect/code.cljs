;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.code
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape-tree :as ctst]
   [app.config :as cfg]
   [app.main.data.event :as ev]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.code-block :refer [code-block]]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
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
   [okulary.core :as l]
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

;; FIXME: this code need to be refactored
(defn get-viewer-objects
  ([]
   (let [route      (deref refs/route)
         page-id    (:page-id (:query-params route))]
     (get-viewer-objects page-id)))
  ([page-id]
   (l/derived
    (fn [state]
      (let [objects (refs/get-viewer-objects state page-id)]
        objects))
    st/state =)))

(defn- use-objects [from]
  (let [page-objects-ref
        (mf/with-memo [from]
          (if (= from :workspace)
            ;; FIXME: fix naming consistency issues
            refs/workspace-page-objects
            (get-viewer-objects)))]
    (mf/deref page-objects-ref)))

(defn- shapes->images
  [shapes]
  (->> shapes
       (keep
        (fn [shape]
          (when-let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))]
            [(:id shape) (cfg/resolve-file-media data)])))))

(defn- replace-map
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
  (let [style-type*    (mf/use-state "css")
        markup-type*   (mf/use-state "html")
        fontfaces-css* (mf/use-state nil)
        images-data*   (mf/use-state nil)

        style-type     (deref style-type*)
        markup-type    (deref markup-type*)
        fontfaces-css  (deref fontfaces-css*)
        images-data    (deref images-data*)

        collapsed*        (mf/use-state #{})
        collapsed-css?    (contains? @collapsed* :css)
        collapsed-markup? (contains? @collapsed* :markup)

        objects        (use-objects from)

        shapes
        (mf/with-memo [shapes frame]
          (mapv #(gsh/translate-to-frame % frame) shapes))

        all-children
        (mf/use-memo
         (mf/deps shapes objects)
         (fn []
           (->> shapes
                (map :id)
                (cfh/selected-with-children objects)
                (ctst/sort-z-index objects)
                (map (d/getf objects)))))

        fonts
        (mf/with-memo [all-children]
          (shapes->fonts all-children))

        images-urls
        (mf/with-memo [all-children]
          (shapes->images all-children))

        style-code
        (mf/use-memo
         (mf/deps fontfaces-css style-type shapes all-children cg/generate-style-code)
         (fn []
           (dm/str
            fontfaces-css "\n"
            (-> (cg/generate-style-code objects style-type shapes all-children)
                (cb/format-code style-type)))))

        markup-code
        (mf/use-memo
         (mf/deps markup-type shapes images-data)
         (fn []
           (cg/generate-formatted-markup-code objects markup-type shapes)))

        on-markup-copied
        (mf/use-fn
         (mf/deps markup-type from)
         (fn []
           (let [origin (if (= :workspace from)
                          "workspace"
                          "viewer")]
             (st/emit! (ptk/event ::ev/event
                                  {::ev/name "copy-inspect-code"
                                   ::ev/origin origin
                                   :type markup-type})))))

        on-style-copied
        (mf/use-fn
         (mf/deps style-type from)
         (fn []
           (let [origin (if (= :workspace from)
                          "workspace"
                          "viewer")]
             (st/emit! (ptk/event ::ev/event
                                  {::ev/name "copy-inspect-style"
                                   ::ev/origin origin
                                   :type style-type})))))

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

        ;; set-style
        ;; (mf/use-fn
        ;;  (fn [value]
        ;;    (reset! style-type* value)))

        set-markup
        (mf/use-fn
         (mf/deps markup-type*)
         (fn [value]
           (reset! markup-type* value)))

        handle-copy-all-code
        (mf/use-fn
         (mf/deps style-code markup-code images-data)
         (fn []
           (wapi/write-to-clipboard (gen-all-code style-code markup-code images-data))
           (let [origin (if (= :workspace from)
                          "workspace"
                          "viewer")]
             (st/emit! (ptk/event ::ev/event
                                  {::ev/name "copy-inspect-code"
                                   ::ev/origin origin
                                   :type "all"})))))

        ;;handle-open-review
        ;;(mf/use-fn
        ;; (fn []
        ;;   (st/emit! (dp/open-preview-selected))))

        handle-collapse
        (mf/use-fn
         (fn [event]
           (let [panel-type (-> (dom/get-current-target event)
                                (dom/get-data "type")
                                (keyword))]
             (swap! collapsed*
                    (fn [collapsed]
                      (if (contains? collapsed panel-type)
                        (disj collapsed panel-type)
                        (conj collapsed panel-type)))))))
        copy-css-fn
        (mf/use-fn
         (mf/deps style-code images-data)
         #(replace-map style-code images-data))

        copy-html-fn
        (mf/use-fn
         (mf/deps markup-code images-data)
         #(replace-map markup-code images-data))]

    (mf/with-effect [fonts]
      (->> (rx/from fonts)
           (rx/merge-map fonts/fetch-font-css)
           (rx/reduce conj [])
           (rx/subs!
            (fn [result]
              (let [css (str/join "\n" result)]
                (reset! fontfaces-css* css))))))

    (mf/with-effect [images-urls]
      (->> (rx/from images-urls)
           (rx/merge-map
            (fn [[_ uri]]
              (->> (http/fetch-data-uri uri true)
                   (rx/catch (fn [_] (rx/of (hash-map uri uri)))))))
           (rx/reduce conj {})
           (rx/subs!
            (fn [result]
              (reset! images-data* result)))))

    [:div {:class (stl/css-case :element-options true
                                :viewer-code-block (= :viewer from))}
     [:div {:class (stl/css :attributes-block)}
      [:button {:class (stl/css :download-button)
                :on-click handle-copy-all-code}
       "Copy all code"]]

     #_[:div.attributes-block
        [:button.download-button {:on-click handle-open-review}
         "Preview"]]

     [:div {:class (stl/css-case :code-block true
                                 :collapsed collapsed-css?)}
      [:div {:class (stl/css :code-row-lang)}
       [:button {:class (stl/css :toggle-btn)
                 :data-type "css"
                 :on-click handle-collapse}
        [:span {:class (stl/css-case
                        :collapsabled-icon true
                        :rotated collapsed-css?)}
         i/arrow]]

       [:div {:class (stl/css :code-lang-option)}
        "CSS"]
       ;; We will have a select when we have more than one option
       ;;  [:& select {:default-value style-type
       ;;              :class (stl/css :code-lang-select)
       ;;              :on-change set-style
       ;;              :options [{:label "CSS" :value "css"}]}]

       [:div {:class (stl/css :action-btns)}
        [:button {:class (stl/css :expand-button)
                  :on-click on-expand}
         i/code]

        [:> copy-button* {:data copy-css-fn
                          :class (stl/css :css-copy-btn)
                          :on-copied on-style-copied}]]]

      (when-not collapsed-css?
        [:div {:class (stl/css :code-row-display)
               :style {:--code-height (dm/str (or style-size 400) "px")}}
         [:& code-block {:type style-type
                         :code style-code}]])

      [:div {:class (stl/css :resize-area)
             :on-pointer-down on-style-pointer-down
             :on-lost-pointer-capture on-style-lost-pointer-capture
             :on-pointer-move on-style-pointer-move}]]

     [:div {:class (stl/css-case :code-block true
                                 :collapsed collapsed-markup?)}
      [:div {:class (stl/css :code-row-lang)}
       [:button {:class (stl/css :toggle-btn)
                 :data-type "markup"
                 :on-click handle-collapse}
        [:span {:class (stl/css-case
                        :collapsabled-icon true
                        :rotated collapsed-markup?)}
         i/arrow]]

       [:& radio-buttons {:selected markup-type
                          :on-change set-markup
                          :class (stl/css :code-lang-options)
                          :wide true
                          :name "listing-style"}
        [:& radio-button {:value "html"
                          :id :html}]
        [:& radio-button {:value "svg"
                          :id :svg}]]

       [:div {:class (stl/css :action-btns)}
        [:button {:class (stl/css :expand-button)
                  :on-click on-expand}
         i/code]

        [:> copy-button* {:data copy-html-fn
                          :class (stl/css :html-copy-btn)
                          :on-copied on-markup-copied}]]]

      (when-not collapsed-markup?
        [:div {:class (stl/css :code-row-display)
               :style {:--code-height (dm/str (or markup-size 400) "px")}}
         [:& code-block {:type markup-type
                         :code markup-code}]])

      [:div {:class (stl/css :resize-area)
             :on-pointer-down on-markup-pointer-down
             :on-lost-pointer-capture on-markup-lost-pointer-capture
             :on-pointer-move on-markup-pointer-move}]]]))
