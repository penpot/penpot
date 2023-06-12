;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.code
  (:require
   ["js-beautify" :as beautify]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape-tree :as ctst]
   [app.config :as cfg]
   [app.main.data.events :as ev]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.code-block :refer [code-block]]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.icons :as i]
   [app.main.ui.shapes.text.fontfaces :refer [shapes->fonts]]
   [app.util.code-gen :as cg]
   [app.util.http :as http]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(defn format-code [code type]
  (let [code (-> code
                 (str/replace "<defs></defs>" "")
                 (str/replace "><" ">\n<"))]
    (cond-> code
      (or (= type "svg") (= type "html")) (beautify/html #js {"indent_size" 2}))))

(defn get-flex-elements [page-id shapes from]
  (let [ids (mapv :id shapes)
        ids (hooks/use-equal-memo ids)
        get-layout-children-refs (mf/use-memo (mf/deps ids page-id from) #(if (= from :workspace)
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
          (when-let [data (or (:metadata shape) (:fill-image shape))]
            [(:id shape) (cfg/resolve-file-media data)])))))

(defn replace-map
  [value map]
  (reduce
   (fn [value [old new]]
     (str/replace value old new))
   value map))

(mf/defc code
  [{:keys [shapes frame on-expand from]}]
  (let [style-type*    (mf/use-state "css")
        markup-type*   (mf/use-state "html")
        fontfaces-css* (mf/use-state nil)
        images-data*   (mf/use-state nil)

        style-type    (deref style-type*)
        markup-type   (deref markup-type*)
        fontfaces-css (deref fontfaces-css*)
        images-data   (deref images-data*)

        shapes      (->> shapes
                         (map #(gsh/translate-to-frame % frame)))

        route      (mf/deref refs/route)
        page-id    (:page-id (:query-params route))
        flex-items (get-flex-elements page-id shapes from)
        objects    (get-objects from)

        ;; TODO REMOVE THIS
        shapes     (->> shapes
                        (map #(assoc % :parent (get objects (:parent-id %))))
                        (map #(assoc % :flex-items flex-items)))

        all-children (->> shapes
                          (map :id)
                          (cph/selected-with-children objects)
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
         (mf/deps fontfaces-css style-type all-children)
         (fn []
           (dm/str
            fontfaces-css "\n"
            (-> (cg/generate-style-code objects style-type all-children)
                (format-code style-type)))))

        markup-code
        (mf/use-memo
         (mf/deps markup-type shapes images-data)
         (fn []
           (-> (cg/generate-markup-code objects markup-type (map :id shapes))
               (format-code markup-type))))

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

        {on-code-pointer-down :on-pointer-down
         on-code-lost-pointer-capture :on-lost-pointer-capture
         on-code-pointer-move :on-pointer-move
         code-size :size}
        (use-resize-hook :code 400 100 800 :y false :bottom)

        set-style
        (mf/use-callback
         (fn [value]
           (reset! style-type* value)))

        set-markup
        (mf/use-callback
         (fn [value]
           (reset! markup-type* value)))]

    (mf/use-effect
     (mf/deps fonts)
     #(->> (rx/from fonts)
           (rx/merge-map fonts/fetch-font-css)
           (rx/reduce conj [])
           (rx/subs
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
           (rx/subs
            (fn [result]
              (reset! images-data* result)))))

    [:div.element-options
     [:div.code-block
      [:div.code-row-lang
       [:& select {:default-value style-type
                   :class "custom-select"
                   :options [{:label "CSS" :value "css"}]
                   :on-change set-style}]
       [:button.expand-button
        {:on-click on-expand}
        i/full-screen]

       [:& copy-button {:data style-code
                        :on-copied on-style-copied}]]

      [:div.code-row-display {:style #js {"--code-height" (str (or code-size 400) "px")}}
       [:& code-block {:type style-type
                       :code style-code}]]

      [:div.resize-area {:on-pointer-down on-code-pointer-down
                         :on-lost-pointer-capture on-code-lost-pointer-capture
                         :on-pointer-move on-code-pointer-move}]]


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

       [:& copy-button {:data (replace-map markup-code images-data)
                        :on-copied on-markup-copied}]]


      [:div.code-row-display
       [:& code-block {:type markup-type
                       :code markup-code}]]]]))
