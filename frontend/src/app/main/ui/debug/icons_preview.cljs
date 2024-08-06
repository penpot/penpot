(ns app.main.ui.debug.icons-preview
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.cursors :as c]
   [app.main.ui.icons :as i]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc icons-gallery
  {::mf/wrap-props false
   ::mf/private true}
  []
  (let [entries   (->> (seq (js/Object.entries i/default))
                       (sort-by first))]
    [:section {:class (stl/css :gallery)}
     (for [[key val] entries]
       [:div {:class (stl/css :gallery-item)
              :key key
              :title key}
        val
        [:span key]])]))

(mf/defc cursors-gallery
  {::mf/wrap-props false
   ::mf/private true}
  []
  (let [rotation (mf/use-state 0)
        entries  (->> (seq (js/Object.entries c/default))
                      (sort-by first))]

    (mf/with-effect []
      (ts/interval 100 #(reset! rotation inc)))

    [:section {:class (stl/css :gallery)}
     (for [[key value] entries]
       (let [value (if (fn? value) (value @rotation) value)]
         [:div {:key key :class (stl/css :gallery-item)}
          [:div {:class (stl/css :cursor)
                 :style {:background-image (-> value (str/replace #"(url\(.*\)).*" "$1"))
                         :cursor value}}]

          [:span (pr-str key)]]))]))


(mf/defc icons-preview
  {::mf/wrap-props false}
  []
  [:article {:class (stl/css :container)}
   [:h2 {:class (stl/css :title)} "Cursors"]
   [:& cursors-gallery]
   [:h2 {:class (stl/css :title)} "Icons"]
   [:& icons-gallery]])

