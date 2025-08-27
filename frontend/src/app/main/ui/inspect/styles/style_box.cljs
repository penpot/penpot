(ns app.main.ui.inspect.styles.style-box
  (:require-macros [app.main.style :as stl])
  (:require
   [rumext.v2 :as mf]))

(defn- attribute->title
  [type]
  (case type
    :variant    "Variant properties"
    :token      "Token Sets & Themes"
    :geometry   "Size & Position"
    :fill       "Fill"
    :stroke     "Stroke"
    :text       "Text"
    :shadow     "Shadow"
    :layout     "Layout"
    :layout-element "Layout Element"
    :visibility "Visibility"
    :svg        "SVG"
    nil))

(mf/defc style-box*
  [{:keys [attribute children]}]
  [:div {:class (stl/css :style-box)}
   [:h3 {:class (stl/css :style-box-header)} (attribute->title attribute)]
   [:div {:class (stl/css :style-box-content)} children]])
