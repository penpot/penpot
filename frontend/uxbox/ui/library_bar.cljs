(ns uxbox.ui.library-bar
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.util :as util]
            [uxbox.ui.icons :as i]))

(defn library-bar-render
  [own]
  (html
   [:div.library-bar
    [:div.library-bar-inside
      [:ul.library-tabs
        [:li "STANDARD"]
        [:li.current "YOUR WIDGETS"]
      ]
      [:ul.library-elements
        [:li
          [:a.btn-primary {:href "#"} "+ New library"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
        [:li
          [:span.element-title "Forms"]
          [:span.element-subtitle "21 widgets"]
        ]
      ]
    ]
   ]))

(def ^:static library-bar
  (util/component
   {:render library-bar-render
    :name "library-bar"
    :mixins [rum/static]}))
