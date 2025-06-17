;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.avatar
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.util.avatars :as avatars]
   [rumext.v2 :as mf]))

(def ^:private schema:avatar
  [:map
   [:class {:optional true} :string]
   [:tag {:optional true} :string]
   [:name {:optional true} [:maybe :string]]
   [:url {:optional true} [:maybe :string]]
   [:color {:optional true} [:maybe :string]]
   [:selected {:optional true} :boolean]
   [:variant {:optional true}
    [:maybe [:enum "S" "M" "L"]]]])

(mf/defc avatar*
  {::mf/schema schema:avatar}

  [{:keys [tag class name color url selected variant] :rest props}]
  (let [variant (or variant "S")
        url     (if (and (some? url) (d/not-empty? url))
                  url
                  (avatars/generate {:name name :color color}))]
    [:> (or tag "div")
     {:class (d/append-class
              class
              (stl/css-case :avatar true
                            :avatar-small (= variant "S")
                            :avatar-medium (= variant "M")
                            :avatar-large (= variant "L")
                            :is-selected selected))
      :style {"--avatar-color" color}
      :title name}
     [:div {:class (stl/css :avatar-image)}
      [:img {:alt name :src url}]]]))
