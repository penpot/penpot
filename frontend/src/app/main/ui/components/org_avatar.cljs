;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.org-avatar
  (:require-macros [app.main.style :as stl])
  (:require
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- get-org-initials
  [name]
  (->> (str/split (str/trim (or name "")) #"\s+")
       (keep #(first (re-seq #"[a-zA-Z]" %)))
       (take 2)
       (map str/upper)
       (apply str)))

(mf/defc org-avatar*
  {::mf/props :obj}
  [{:keys [org size]}]
  (let [name         (:name org)
        custom-photo (:organization-custom-photo org)
        avatar-bg    (:organization-avatar-bg-url org)
        initials     (get-org-initials name)]

    (if custom-photo
      [:img {:src     custom-photo
             :class   (stl/css-case :org-avatar true
                                    :org-avatar-custom true
                                    :org-avatar-xxxl (= size "xxxl")
                                    :org-avatar-xxl (= size "xxl"))
             :alt     name}]
      [:div {:class       (stl/css-case :org-avatar true
                                        :org-avatar-xxxl (= size "xxxl")
                                        :org-avatar-xxl (= size "xxl"))
             :aria-hidden "true"}
       [:img {:src   avatar-bg
              :class (stl/css :org-avatar-bg)
              :alt   ""}]
       (when (seq initials)
         [:span {:class (stl/css-case :org-avatar-initials true
                                      :size-initials-xxxl (= size "xxxl")
                                      :size-initials-xxl (= size "xxl"))}
          initials])])))
