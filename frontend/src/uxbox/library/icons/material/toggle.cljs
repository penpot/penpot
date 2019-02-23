;; This work is licensed under CC BY 4.0.
;; The original source can be found here:
;; https://github.com/google/material-design-icons

(ns uxbox.library.icons.material.toggle
  (:require [uxbox.util.uuid :as uuid]))

(def +icons+
  [{:name "Check Box"
    :id (uuid/random)
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 6H10c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4V10c0-2.21-1.79-4-4-4zM20 34L10 24l2.83-2.83L20 28.34l15.17-15.17L38 16 20 34z"}]}

   {:name "Check Box Outline Blank"
    :id (uuid/random)
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 10v28H10V10h28m0-4H10c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4V10c0-2.21-1.79-4-4-4z"}]}

   {:name "Radio Button Off"
    :id (uuid/random)
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4C12.95 4 4 12.95 4 24s8.95 20 20 20 20-8.95 20-20S35.05 4 24 4zm0 36c-8.84 0-16-7.16-16-16S15.16 8 24 8s16 7.16 16 16-7.16 16-16 16z"}]}

   {:name "Radio Button On"
    :id (uuid/random)
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 14c-5.52 0-10 4.48-10 10s4.48 10 10 10 10-4.48 10-10-4.48-10-10-10zm0-10C12.95 4 4 12.95 4 24s8.95 20 20 20 20-8.95 20-20S35.05 4 24 4zm0 36c-8.84 0-16-7.16-16-16S15.16 8 24 8s16 7.16 16 16-7.16 16-16 16z"}]}

   {:name "Star"
    :id (uuid/random)
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"}]}

   {:name "Star Half"
    :id (uuid/random)
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M22 9.74l-7.19-.62L12 2.5 9.19 9.13 2 9.74l5.46 4.73-1.64 7.03L12 17.77l6.18 3.73-1.63-7.03L22 9.74zM12 15.9V6.6l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.9z"}]}

   {:name "Star Outline"
    :id (uuid/random)
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z"}]}])
