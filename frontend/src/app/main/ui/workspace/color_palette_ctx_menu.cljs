;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.color-palette-ctx-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.refs :as refs]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private xf:sample-colors
  (comp (map val)
        (take 7)))

(defn- extract-colors
  [{:keys [data] :as file}]
  (let [colors (into [] xf:sample-colors (:colors data))]
    (-> file
        (assoc :colors colors)
        (dissoc :data))))

(mf/defc color-palette-ctx-menu*
  [{:keys [show on-close on-select selected]}]
  (let [recent-colors (mf/deref refs/recent-colors)
        libraries     (mf/deref refs/libraries)

        file-id       (mf/use-ctx ctx/current-file-id)
        local-colors  (mf/with-memo [libraries file-id]
                        (let [colors (dm/get-in libraries [file-id :data :colors])]
                          (into [] xf:sample-colors colors)))

        libraries     (mf/with-memo [libraries file-id]
                        (->> (dissoc libraries file-id)
                             (vals)
                             (mapv extract-colors)))

        recent-colors (mf/with-memo [recent-colors]
                        (->> (reverse recent-colors)
                             (take 7)
                             (map-indexed (fn [index color]
                                            (assoc color ::id (dm/str index))))
                             (vec)))]

    [:& dropdown {:show show :on-close on-close}
     [:ul {:class (stl/css :palette-menu)}
      (for [{:keys [id colors] :as library} libraries]
        [:li  {:class (stl/css-case :palette-library true
                                    :selected (= selected id))
               :key (dm/str "library-" id)
               :on-click on-select
               :data-palette (dm/str id)}
         [:div {:class (stl/css :option-wrapper)}
          [:div {:class (stl/css :library-name)}
           [:div {:class (stl/css :lib-name-wrapper)}
            [:span {:class (stl/css :lib-name)}
             (dm/str (:name library))]
            [:span {:class (stl/css :lib-num)}
             (dm/str "(" (count colors) ")")]]
           (when (= selected id)
             [:span {:class (stl/css :icon-wrapper)}
              i/tick])]
          [:div {:class (stl/css :color-sample)
                 :style {:--bullet-size "20px"}}
           (for [color colors]
             [:& cb/color-bullet {:key (dm/str (:id color))
                                  :mini true
                                  :color color}])]]])

      [:li {:class (stl/css-case
                    :file-library true
                    :selected (= selected :file))
            :on-click on-select
            :data-palette "file"}

       [:div {:class (stl/css :option-wrapper)}
        [:div {:class (stl/css :library-name)}

         [:div {:class (stl/css :lib-name-wrapper)}
          [:span {:class (stl/css :lib-name)}
           (dm/str (tr "workspace.libraries.colors.file-library"))]
          [:span {:class (stl/css :lib-num)}
           (dm/str "(" (count local-colors) ")")]]

         (when (= selected :file)
           [:span {:class (stl/css :icon-wrapper)}
            i/tick])]
        [:div {:class (stl/css :color-sample)
               :style {:--bullet-size "20px"}}
         (for [color local-colors]
           [:& cb/color-bullet {:key (dm/str (:id color))
                                :mini true
                                :color color}])]]]

      [:li {:class (stl/css
                    :recent-colors true
                    :selected (= selected :recent))
            :on-click on-select
            :data-palette "recent"}
       [:div {:class (stl/css :option-wrapper)}
        [:div {:class (stl/css :library-name)}
         [:div {:class (stl/css :lib-name-wrapper)}
          [:span {:class (stl/css :lib-name)}
           (dm/str (tr "workspace.libraries.colors.recent-colors"))]
          [:span {:class (stl/css :lib-num)}
           (dm/str "(" (count recent-colors) ")")]]

         (when (= selected :recent)
           [:span {:class (stl/css :icon-wrapper)}
            i/tick])]
        [:div {:class (stl/css :color-sample)
               :style {:--bullet-size "20px"}}

         (for [color recent-colors]
           [:& cb/color-bullet {:key (dm/str (::id color))
                                :mini true
                                :color color}])]]]]]))
