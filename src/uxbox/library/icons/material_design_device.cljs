(ns uxbox.library.icons.material-design-device
  (:require [sablono.core :as html :refer-macros [html]]))

(def +icons+
  [{:name "Access Alarm"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M44 11.44l-9.19-7.71-2.57 3.06 9.19 7.71 2.57-3.06zm-28.24-4.66l-2.57-3.06-9.19 7.71 2.57 3.06 9.19-7.71zm9.24 9.22h-3v12l9.49 5.71 1.51-2.47-8-4.74v-10.5zm-1.01-8c-9.95 0-17.99 8.06-17.99 18s8.04 18 17.99 18 18.01-8.06 18.01-18-8.06-18-18.01-18zm.01 32c-7.73 0-14-6.27-14-14s6.27-14 14-14 14 6.27 14 14-6.26 14-14 14z"}]}

   {:name "Access Alarms"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M44 11.44l-9.19-7.71-2.57 3.06 9.19 7.71 2.57-3.06zm-28.24-4.66l-2.57-3.06-9.19 7.71 2.57 3.06 9.19-7.71zm9.24 9.22h-3v12l9.49 5.71 1.51-2.47-8-4.74v-10.5zm-1.01-8c-9.95 0-17.99 8.06-17.99 18s8.04 18 17.99 18 18.01-8.06 18.01-18-8.06-18-18.01-18zm.01 32c-7.73 0-14-6.27-14-14s6.27-14 14-14 14 6.27 14 14-6.26 14-14 14z"}]}

   {:name "Access Times"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g {}
           [:path
            {:style {:stroke nil}
             :key "1"
             :d
             "M23.99 4c-11.05 0-19.99 8.95-19.99 20s8.94 20 19.99 20c11.05 0 20.01-8.95 20.01-20s-8.96-20-20.01-20zm.01 36c-8.84 0-16-7.16-16-16s7.16-16 16-16 16 7.16 16 16-7.16 16-16 16z"
             :fill-opacity ".9"}]
           [:path
            {:style {:stroke nil}
             :key "2"
             :d "M25 14h-3v12l10.49 6.3 1.51-2.46-9-5.34z"
             :fill-opacity ".9"}]]}

   {:name "Add Alarm"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M15.76 6.78l-2.57-3.06-9.19 7.71 2.57 3.06 9.19-7.71zm28.24 4.66l-9.19-7.71-2.57 3.06 9.19 7.71 2.57-3.06zm-20.01-3.44c-9.95 0-17.99 8.06-17.99 18s8.04 18 17.99 18 18.01-8.06 18.01-18-8.06-18-18.01-18zm.01 32c-7.73 0-14-6.27-14-14s6.27-14 14-14 14 6.27 14 14-6.26 14-14 14zm2-22h-4v6h-6v4h6v6h4v-6h6v-4h-6v-6z"}]}

   {:name "Airplanemode Off"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M26 18v-11c0-1.66-1.34-3-3-3s-3 1.34-3 3v7.36l15.65 15.65 6.35 1.99v-4l-16-10zm-20-7.45l9.97 9.97-11.97 7.48v4l16-5v11l-4 3v3l7-2 7 2v-3l-4-3v-7.45l11.45 11.45 2.55-2.55-31.45-31.45-2.55 2.55z"}]}

   {:name "Airplanemode On"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M42 32v-4l-16-10v-11c0-1.66-1.34-3-3-3s-3 1.34-3 3v11l-16 10v4l16-5v11l-4 3v3l7-2 7 2v-3l-4-3v-11l16 5z"}]}

   {:name "Battery 20"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g [:path
               {:style {:stroke nil}
                :d
                "M14 34v7.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-7.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M34 10.67c0-1.48-1.19-2.67-2.67-2.67h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h20v-23.33z"
             :fill-opacity ".3"}]]}

   {:name "Battery 30"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 30v11.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-11.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M34 10.67c0-1.48-1.19-2.67-2.67-2.67h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h20v-23.33z"
             :fill-opacity ".3"}]]}

   {:name "Battery 50"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 26v15.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-15.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M34 10.67c0-1.48-1.19-2.67-2.67-2.67h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h20v-23.33z"
             :fill-opacity ".3"}]]}

   {:name "Battery 60"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 22v19.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-19.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M34 10.67c0-1.48-1.19-2.67-2.67-2.67h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h20v-23.33z"
             :fill-opacity ".3"}]]}

   {:name "Battery 80"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 18v23.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-23.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M34 10.67c0-1.48-1.19-2.67-2.67-2.67h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h20v-23.33z"
             :fill-opacity ".3"}]]}

   {:name "Battery 90"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 16v25.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-25.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M34 10.67c0-1.48-1.19-2.67-2.67-2.67h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h20v-23.33z"
             :fill-opacity ".3"}]]}

   {:name "Battery Alert"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v30.67c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-30.67c-.01-1.48-1.2-2.67-2.68-2.67zm-5.33 28h-4v-4h4v4zm0-8h-4v-10h4v10z"}]}

   {:name "Battery Charging 20"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M22 40v-6h-8v7.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-7.33h-8.8l-3.21 6z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h8v-5h-4l8-15v11h4l-4.8 9h8.8v-23.33c0-1.48-1.19-2.67-2.67-2.67z"
             :fill-opacity ".3"}]]}

   {:name "Battery Charging 30"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 30v11.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-11.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h8v-5h-4l8-15v11h4l-4.8 9h8.8v-23.33c0-1.48-1.19-2.67-2.67-2.67z"
             :fill-opacity ".3"}]]}

   {:name "Battery Charging 50"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 26v15.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-15.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h8v-5h-4l8-15v11h4l-4.8 9h8.8v-23.33c0-1.48-1.19-2.67-2.67-2.67z"
             :fill-opacity ".3"}]]}

   {:name "Battery Charging 60"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 22v19.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-19.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h8v-5h-4l8-15v11h4l-4.8 9h8.8v-23.33c0-1.48-1.19-2.67-2.67-2.67z"
             :fill-opacity ".3"}]]}

   {:name "Battery Charging 80"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 18v23.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-23.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h8v-5h-4l8-15v11h4l-4.8 9h8.8v-23.33c0-1.48-1.19-2.67-2.67-2.67z"
             :fill-opacity ".3"}]]}

   {:name "Battery Charging 90"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M14 16v25.33c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-25.33h-20.01z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v23.33h8v-5h-4l8-15v11h4l-4.8 9h8.8v-23.33c0-1.48-1.19-2.67-2.67-2.67z"
             :fill-opacity ".3"}]]}

   {:name "Battery Charging Full"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v30.67c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-30.67c-.01-1.48-1.2-2.67-2.68-2.67zm-9.33 32v-11h-4l8-15v11h4l-8 15z"}]}

   {:name "Battery Full"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v30.67c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-30.67c-.01-1.48-1.2-2.67-2.68-2.67z"}]}

   {:name "Battery Std"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v30.67c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-30.67c-.01-1.48-1.2-2.67-2.68-2.67z"}]}

   {:name "Battery Unknown"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M31.33 8h-3.33v-4h-8v4h-3.33c-1.48 0-2.67 1.19-2.67 2.67v30.67c0 1.47 1.19 2.67 2.67 2.67h14.67c1.47 0 2.67-1.19 2.67-2.67v-30.67c-.01-1.48-1.2-2.67-2.68-2.67zm-5.43 27.9h-3.8v-3.8h3.8v3.8zm2.7-10.52s-.76.84-1.34 1.42c-.97.97-1.66 2.29-1.66 3.2h-3.2c0-1.66.92-3.05 1.86-3.99l1.86-1.89c.54-.54.88-1.29.88-2.12 0-1.66-1.34-3-3-3s-3 1.34-3 3h-3c0-3.31 2.69-6 6-6s6 2.69 6 6c0 1.32-.53 2.52-1.4 3.38z"}]}

   {:name "Bluetooth"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M35.41 15.41l-11.41-11.41h-2v15.17l-9.17-9.17-2.83 2.83 11.17 11.17-11.17 11.17 2.83 2.83 9.17-9.17v15.17h2l11.41-11.41-8.58-8.59 8.58-8.59zm-9.41-3.75l3.76 3.76-3.76 3.75v-7.51zm3.76 20.93l-3.76 3.75v-7.52l3.76 3.77z"}]}

   {:name "Bluetooth Connected"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 24l-4-4-4 4 4 4 4-4zm21.41-8.59l-11.41-11.41h-2v15.17l-9.17-9.17-2.83 2.83 11.17 11.17-11.17 11.17 2.83 2.83 9.17-9.17v15.17h2l11.41-11.41-8.58-8.59 8.58-8.59zm-9.41-3.75l3.76 3.76-3.76 3.75v-7.51zm3.76 20.93l-3.76 3.75v-7.52l3.76 3.77zm8.24-12.59l-4 4 4 4 4-4-4-4z"}]}

   {:name "Bluetooth Disabled"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M26 11.83l3.76 3.76-3.2 3.2 2.83 2.83 6.03-6.03-11.42-11.42h-2v10.06l4 4v-6.4zm-15.17-3.66l-2.83 2.83 13.17 13.17-11.17 11.17 2.83 2.83 9.17-9.17v15.17h2l8.59-8.59 4.59 4.59 2.82-2.83-29.17-29.17zm15.17 28.34v-7.51l3.76 3.76-3.76 3.75z"}]}

   {:name "Bluetooth Searching"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M28.48 24.02l4.64 4.64c.56-1.45.88-3.02.88-4.66 0-1.63-.31-3.19-.86-4.63l-4.66 4.65zm10.58-10.59l-2.53 2.53c1.25 2.41 1.97 5.14 1.97 8.05s-.72 5.63-1.97 8.05l2.4 2.4c1.93-3.1 3.07-6.73 3.07-10.63 0-3.82-1.09-7.37-2.94-10.4zm-7.65 1.98l-11.41-11.41h-2v15.17l-9.17-9.17-2.83 2.83 11.17 11.17-11.17 11.17 2.83 2.83 9.17-9.17v15.17h2l11.41-11.41-8.58-8.59 8.58-8.59zm-9.41-3.75l3.76 3.76-3.76 3.75v-7.51zm3.76 20.93l-3.76 3.75v-7.52l3.76 3.77z"}]}

   {:name "Brightness Auto"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M21.7 25.3h4.6l-2.3-7.3-2.3 7.3zm18.3-7.93v-9.37h-9.37l-6.63-6.63-6.63 6.63h-9.37v9.37l-6.63 6.63 6.63 6.63v9.37h9.37l6.63 6.63 6.63-6.63h9.37v-9.37l6.63-6.63-6.63-6.63zm-11.4 14.63l-1.4-4h-6.4l-1.4 4h-3.8l6.4-18h4l6.4 18h-3.8z"}]}

   {:name "Brightness High"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 17.37v-9.37h-9.37l-6.63-6.63-6.63 6.63h-9.37v9.37l-6.63 6.63 6.63 6.63v9.37h9.37l6.63 6.63 6.63-6.63h9.37v-9.37l6.63-6.63-6.63-6.63zm-16 18.63c-6.63 0-12-5.37-12-12s5.37-12 12-12 12 5.37 12 12-5.37 12-12 12zm0-20c-4.42 0-8 3.58-8 8s3.58 8 8 8 8-3.58 8-8-3.58-8-8-8z"}]}

   {:name "Brightness Low"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 30.63l6.63-6.63-6.63-6.63v-9.37h-9.37l-6.63-6.63-6.63 6.63h-9.37v9.37l-6.63 6.63 6.63 6.63v9.37h9.37l6.63 6.63 6.63-6.63h9.37v-9.37zm-16 5.37c-6.63 0-12-5.37-12-12s5.37-12 12-12 12 5.37 12 12-5.37 12-12 12z"}]}

   {:name "Brightness Medium"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 30.63l6.63-6.63-6.63-6.63v-9.37h-9.37l-6.63-6.63-6.63 6.63h-9.37v9.37l-6.63 6.63 6.63 6.63v9.37h9.37l6.63 6.63 6.63-6.63h9.37v-9.37zm-16 5.37v-24c6.63 0 12 5.37 12 12s-5.37 12-12 12z"}]}

   {:name "Data Usage"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M26 4.1v6.06c6.78.97 12 6.79 12 13.84 0 1.79-.35 3.5-.96 5.07l5.2 3.07c1.11-2.49 1.76-5.23 1.76-8.14 0-10.37-7.89-18.89-18-19.9zm-2 33.9c-7.73 0-14-6.27-14-14 0-7.05 5.22-12.87 12-13.84v-6.06c-10.12 1-18 9.53-18 19.9 0 11.05 8.94 20 19.99 20 6.62 0 12.47-3.23 16.11-8.18l-5.19-3.06c-2.56 3.19-6.49 5.24-10.91 5.24z"}]}

   {:name "Developer Mode"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 10.17h20v4h4v-8c0-2.21-1.79-3.98-4-3.98l-20-.02c-2.21 0-4 1.79-4 4v8h4v-4zm16.83 23.17l9.17-9.17-9.17-9.17-2.83 2.83 6.34 6.34-6.34 6.35 2.83 2.82zm-10.83-2.82l-6.34-6.34 6.34-6.35-2.83-2.83-9.17 9.17 9.17 9.17 2.83-2.82zm14 7.65h-20v-4h-4v8c0 2.21 1.79 4 4 4h20c2.21 0 4-1.79 4-4v-8h-4v4z"}]}

   {:name "Devices"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M8 12h36v-4h-36c-2.21 0-4 1.79-4 4v22h-4v6h28v-6h-20v-22zm38 4h-12c-1.1 0-2 .9-2 2v20c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2v-20c0-1.1-.9-2-2-2zm-2 18h-8v-14h8v14z"}]}

   {:name "DVR"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M42 6h-36c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h10v4h16v-4h10c2.21 0 3.98-1.79 3.98-4l.02-24c0-2.21-1.79-4-4-4zm0 28h-36v-24h36v24zm-4-18h-22v4h22v-4zm0 8h-22v4h22v-4zm-24-8h-4v4h4v-4zm0 8h-4v4h4v-4z"}]}

   {:name "GPS Fixed"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 16c-4.42 0-8 3.58-8 8s3.58 8 8 8 8-3.58 8-8-3.58-8-8-8zm17.88 6c-.92-8.34-7.54-14.96-15.88-15.88v-4.12h-4v4.12c-8.34.92-14.96 7.54-15.88 15.88h-4.12v4h4.12c.92 8.34 7.54 14.96 15.88 15.88v4.12h4v-4.12c8.34-.92 14.96-7.54 15.88-15.88h4.12v-4h-4.12zm-17.88 16c-7.73 0-14-6.27-14-14s6.27-14 14-14 14 6.27 14 14-6.27 14-14 14z"}]}

   {:name "GPS Not Fixed"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M41.88 22c-.92-8.34-7.54-14.96-15.88-15.88v-4.12h-4v4.12c-8.34.92-14.96 7.54-15.88 15.88h-4.12v4h4.12c.92 8.34 7.54 14.96 15.88 15.88v4.12h4v-4.12c8.34-.92 14.96-7.54 15.88-15.88h4.12v-4h-4.12zm-17.88 16c-7.73 0-14-6.27-14-14s6.27-14 14-14 14 6.27 14 14-6.27 14-14 14z"}]}

   {:name "GPS Off"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M41.88 22c-.92-8.34-7.54-14.96-15.88-15.88v-4.12h-4v4.12c-2.26.25-4.38.93-6.31 1.94l3 3c1.64-.68 3.43-1.06 5.31-1.06 7.73 0 14 6.27 14 14 0 1.88-.38 3.67-1.05 5.31l3 3c1.01-1.93 1.68-4.05 1.93-6.31h4.12v-4h-4.12zm-35.88-13.45l4.07 4.07c-2.14 2.62-3.56 5.84-3.95 9.38h-4.12v4h4.12c.92 8.34 7.54 14.96 15.88 15.88v4.12h4v-4.12c3.54-.39 6.76-1.82 9.38-3.96l4.07 4.08 2.55-2.54-33.45-33.46-2.55 2.55zm26.53 26.53c-2.36 1.82-5.31 2.92-8.53 2.92-7.73 0-14-6.27-14-14 0-3.22 1.1-6.17 2.92-8.53l19.61 19.61z"}]}

   {:name "Location Disabled"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M41.88 22c-.92-8.34-7.54-14.96-15.88-15.88v-4.12h-4v4.12c-2.26.25-4.38.93-6.31 1.94l3 3c1.64-.68 3.43-1.06 5.31-1.06 7.73 0 14 6.27 14 14 0 1.88-.38 3.67-1.05 5.31l3 3c1.01-1.93 1.68-4.05 1.93-6.31h4.12v-4h-4.12zm-35.88-13.45l4.07 4.07c-2.14 2.62-3.56 5.84-3.95 9.38h-4.12v4h4.12c.92 8.34 7.54 14.96 15.88 15.88v4.12h4v-4.12c3.54-.39 6.76-1.82 9.38-3.96l4.07 4.08 2.55-2.54-33.45-33.46-2.55 2.55zm26.53 26.53c-2.36 1.82-5.31 2.92-8.53 2.92-7.73 0-14-6.27-14-14 0-3.22 1.1-6.17 2.92-8.53l19.61 19.61z"}]}

   {:name "Location Searching"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M41.88 22.17c-.92-8.34-7.54-14.96-15.88-15.88v-4.12h-4v4.12c-8.34.92-14.96 7.54-15.88 15.88h-4.12v4h4.12c.92 8.34 7.54 14.96 15.88 15.88v4.12h4v-4.12c8.34-.92 14.96-7.54 15.88-15.88h4.12v-4h-4.12zm-17.88 16c-7.73 0-14-6.27-14-14s6.27-14 14-14 14 6.27 14 14-6.27 14-14 14z"}]}

   {:name "Multitrack Audio"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 36h4v-24h-4v24zm8 8h4v-40h-4v40zm-16-16h4v-8h-4v8zm24 8h4v-24h-4v24zm8-16v8h4v-8h-4z"}]}

   {:name "Network Cell"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path {:style {:stroke nil}
                   :d "M34 14l-30 30h30z"}]
           [:path {:style {:stroke nil}
                   :d "M4 44h40v-40z"
                   :fill-opacity ".3"}]]}

   {:name "Network Wifi"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M7.07 21.91l16.92 21.07.01.02.02-.02 16.92-21.07c-.86-.66-7.32-5.91-16.94-5.91-9.63 0-16.08 5.25-16.93 5.91z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8s-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"
             :fill-opacity ".3"}]]}

   {:name "NFC"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 4.17h-32c-2.21 0-4 1.79-4 4v32c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4v-32c0-2.21-1.79-4-4-4zm0 36h-32v-32h32v32zm-4-28h-10c-2.21 0-4 1.79-4 4v4.55c-1.19.69-2 1.97-2 3.45 0 2.21 1.79 4 4 4s4-1.79 4-4c0-1.48-.81-2.75-2-3.45v-4.55h6v16h-16v-16h4v-4h-8v24h24v-24z"}]}

   {:name "Now Wallpaper"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M8 8h14v-4h-14c-2.21 0-4 1.79-4 4v14h4v-14zm12 18l-8 10h24l-6-8-4.06 5.42-5.94-7.42zm14-9c0-1.66-1.34-3-3-3s-3 1.34-3 3 1.34 3 3 3 3-1.34 3-3zm6-13h-14v4h14v14h4v-14c0-2.21-1.79-4-4-4zm0 36h-14v4h14c2.21 0 4-1.79 4-4v-14h-4v14zm-32-14h-4v14c0 2.21 1.79 4 4 4h14v-4h-14v-14z"}]}

   {:name "Now Widgets"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M26 26v16h16v-16h-16zm-20 16h16v-16h-16v16zm0-36v16h16v-16h-16zm27.31-2.63l-11.31 11.32 11.31 11.31 11.31-11.31-11.31-11.32z"}]}

   {:name "Screen Lock Landscape"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M42 10h-36c-2.21 0-4 1.79-4 4v20c0 2.21 1.79 4 4 4h36c2.21 0 4-1.79 4-4v-20c0-2.21-1.79-4-4-4zm-4 24h-28v-20h28v20zm-18-2h8c1.11 0 2-.9 2-2v-6c0-1.1-.89-2-2-2v-2c0-2.21-1.79-4-4-4s-4 1.79-4 4v2c-1.11 0-2 .9-2 2v6c0 1.1.89 2 2 2zm1.6-12c0-1.33 1.07-2.4 2.4-2.4 1.33 0 2.4 1.08 2.4 2.4v2h-4.8v-2z"}]}

   {:name "Screen Lock Portrait"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M20 32h8c1.11 0 2-.9 2-2v-6c0-1.1-.89-2-2-2v-2c0-2.21-1.79-4-4-4s-4 1.79-4 4v2c-1.11 0-2 .9-2 2v6c0 1.1.89 2 2 2zm1.6-12c0-1.33 1.07-2.4 2.4-2.4 1.33 0 2.4 1.08 2.4 2.4v2h-4.8v-2zm12.4-18h-20c-2.21 0-4 1.79-4 4v36c0 2.21 1.79 4 4 4h20c2.21 0 4-1.79 4-4v-36c0-2.21-1.79-4-4-4zm0 36h-20v-28h20v28z"}]}

   {:name "Screen Lock Rotation"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M46.51 25.54l-5.14-5.14-2.83 2.83 4.43 4.43-11.31 11.31-22.63-22.63 11.31-11.31 4.19 4.19 2.83-2.83-4.9-4.9c-1.17-1.17-3.07-1.17-4.24 0l-12.73 12.73c-1.17 1.17-1.17 3.07 0 4.24l24.04 24.04c1.17 1.17 3.07 1.17 4.24 0l12.73-12.73c1.18-1.16 1.18-3.06.01-4.23zm-29.58 15.43c-6.53-3.1-11.22-9.45-11.93-16.97h-3c1.02 12.32 11.32 22 23.9 22 .45 0 .88-.04 1.33-.07l-7.63-7.63-2.67 2.67zm15.07-22.97h10c1.11 0 2-.9 2-2v-8c0-1.1-.89-2-2-2v-1c0-2.76-2.24-5-5-5s-5 2.24-5 5v1c-1.11 0-2 .9-2 2v8c0 1.1.89 2 2 2zm1.6-13c0-1.88 1.52-3.4 3.4-3.4s3.4 1.52 3.4 3.4v1h-6.8v-1z"}]}

   {:name "Screen Rotation"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M32.97 5.03c6.53 3.1 11.22 9.45 11.93 16.97h3c-1.02-12.32-11.32-22-23.9-22-.45 0-.88.04-1.33.07l7.63 7.63 2.67-2.67zm-12.51-1.54c-1.17-1.17-3.07-1.17-4.24 0l-12.73 12.73c-1.17 1.17-1.17 3.07 0 4.24l24.04 24.04c1.17 1.17 3.07 1.17 4.24 0l12.73-12.73c1.17-1.17 1.17-3.07 0-4.24l-24.04-24.04zm9.2 38.89l-24.05-24.04 12.73-12.73 24.04 24.04-12.72 12.73zm-14.63.59c-6.53-3.1-11.22-9.45-11.93-16.97h-3c1.02 12.32 11.32 22 23.9 22 .45 0 .88-.04 1.33-.07l-7.63-7.63-2.67 2.67z"}]}

   {:name "SD Storage"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M36 4h-16l-11.96 12-.04 24c0 2.2 1.8 4 4 4h24c2.2 0 4-1.8 4-4v-32c0-2.2-1.8-4-4-4zm-12 12h-4v-8h4v8zm6 0h-4v-8h4v8zm6 0h-4v-8h4v8z"}]}

   {:name "System Settings Daydream"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M18 32h13c2.76 0 5-2.24 5-5s-2.24-5-5-5h-.1c-.49-3.39-3.38-6-6.9-6-2.8 0-5.2 1.66-6.32 4.04h-.33c-3.01.32-5.35 2.87-5.35 5.96 0 3.31 2.69 6 6 6zm24-26h-36c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h36c2.21 0 4-1.79 4-4v-28c0-2.21-1.79-4-4-4zm0 32.03h-36v-28.06h36v28.06z"}]}

   {:name "Signal Cellular 0 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M4 44h40v-40z"
            :fill-opacity ".3"}]}

   {:name "Signal Cellular 1 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path {:style {:stroke nil}
                   :d "M24 24l-20 20h20z"}]
           [:path {:style {:stroke nil}
                   :d "M4 44h40v-40z"
                   :fill-opacity ".3"}]]}

   {:name "Signal Cellular 2 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path {:style {:stroke nil}
                   :d "M28 20l-24 24h24z"}]
           [:path {:style {:stroke nil}
                   :d "M4 44h40v-40z"
                   :fill-opacity ".3"}]]}

   {:name "Signal Cellular 3 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path {:style {:stroke nil}
                   :d "M34 14l-30 30h30z"}]
           [:path {:style {:stroke nil}
                   :d "M4 44h40v-40z"
                   :fill-opacity ".3"}]]}

   {:name "Signal Cellular 4 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path {:style {:stroke nil}
                  :d "M4 44h40v-40z"}]}

   {:name "Signal Cellular Connected No Internet 0 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path {:style {:stroke nil}
                   :d "M40 44h4v-4h-4v4zm0-24v16h4v-16h-4z"}]
           [:path
            {:style {:stroke nil}
             :d "M44 16v-12l-40 40h32v-28z"
             :fill-opacity ".3"}]]}

   {:name "Signal Cellular Connected No Internet 1 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d "M40 20v16h4v-16h-4zm-16 24v-20l-20 20h20zm16 0h4v-4h-4v4z"}]
           [:path
            {:style {:stroke nil}
             :d "M44 16v-12l-40 40h32v-28z"
             :fill-opacity ".3"}]]}

   {:name "Signal Cellular Connected No Internet 2 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d "M28 44v-24l-24 24h24zm12-24v16h4v-16h-4zm0 24h4v-4h-4v4z"}]
           [:path
            {:style {:stroke nil}
             :d "M44 16v-12l-40 40h32v-28z"
             :fill-opacity ".3"}]]}

   {:name "Signal Cellular Connected No Internet 3 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d "M34 44v-30l-30 30h30zm6-24v16h4v-16h-4zm0 24h4v-4h-4v4z"}]
           [:path
            {:style {:stroke nil}
             :d "M44 16v-12l-40 40h32v-28z"
             :fill-opacity ".3"}]]}

   {:name "Signal Cellular No Sim"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M37.98 10c0-2.21-1.77-4-3.98-4h-14l-4.68 4.68 22.68 22.68-.02-23.36zm-30.68-2.24l-2.54 2.54 5.24 5.25v22.45c0 2.21 1.79 4 4 4h20.02c.7 0 1.35-.2 1.92-.51l3.76 3.76 2.54-2.55-34.94-34.94z"}]}

   {:name "Signal Cellular Null"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M40 13.66v26.34h-26.34l26.34-26.34m4-9.66l-40 40h40v-40z"}]}

   {:name "Signal Cellular Off"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M42 2l-17.18 17.18 17.18 17.18v-34.36zm-32.45 7l-2.55 2.54 12.73 12.73-17.73 17.73h35.45l4 4 2.55-2.55-34.45-34.45z"}]}

   {:name "Signal Wifi 0 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8-13.43 0-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"
            :fill-opacity ".3"}]}

   {:name "Signal Wifi 1 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M13.34 29.72l10.65 13.27.01.01.01-.01 10.65-13.27c-.53-.41-4.6-3.72-10.66-3.72s-10.13 3.31-10.66 3.72z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8-13.43 0-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"
             :fill-opacity ".3"}]]}

   {:name "Signal Wifi 2 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M9.58 25.03l14.41 17.95.01.02.01-.02 14.41-17.95c-.72-.56-6.22-5.03-14.42-5.03s-13.7 4.47-14.42 5.03z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8-13.43 0-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"
             :fill-opacity ".3"}]]}

   {:name "Signal Wifi 3 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M7.07 21.91l16.92 21.07.01.02.02-.02 16.92-21.07c-.86-.66-7.32-5.91-16.94-5.91-9.63 0-16.08 5.25-16.93 5.91z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8-13.43 0-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"
             :fill-opacity ".3"}]]}

   {:name "Signal Wifi 4 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8s-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"}]}

   {:name "Signal Wifi Off"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M47.28 14c-.9-.68-9.85-8-23.28-8-3.01 0-5.78.38-8.3.96l20.66 20.64 10.92-13.6zm-40.73-11.11l-2.55 2.55 4.11 4.11c-4.28 1.97-6.92 4.1-7.39 4.46l23.26 28.98.02.01.02-.02 7.8-9.72 6.63 6.63 2.55-2.55-34.45-34.45z"}]}

   {:name "Signal Wifi Statusbar 0 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8-13.43 0-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"
            :fill-opacity ".3"}]}

   {:name "Signal Wifi Statusbar 1 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M13.34 29.72l10.65 13.27.01.01.01-.01 10.65-13.27c-.53-.41-4.6-3.72-10.66-3.72s-10.13 3.31-10.66 3.72z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8-13.43 0-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"
             :fill-opacity ".3"}]]}

   {:name "Signal Wifi Statusbar 2 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M9.58 25.03l14.41 17.95.01.02.01-.02 14.41-17.95c-.72-.56-6.22-5.03-14.42-5.03s-13.7 4.47-14.42 5.03z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8-13.43 0-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"
             :fill-opacity ".3"}]]}

   {:name "Signal Wifi Statusbar 3 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M7.07 21.91l16.92 21.07.01.02.02-.02 16.92-21.07c-.86-.66-7.32-5.91-16.94-5.91-9.63 0-16.08 5.25-16.93 5.91z"}]
           [:path
            {:style {:stroke nil}
             :d
             "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8-13.43 0-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"
             :fill-opacity ".3"}]]}

   {:name "Signal Wifi Statusbar 4 Bar"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24.02 42.98l23.26-28.98c-.9-.68-9.85-8-23.28-8s-22.38 7.32-23.28 8l23.26 28.98.02.02.02-.02z"}]}

   {:name "Signal Wifi Statusbar Null"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M13 4c4.25 0 7.62 1.51 9.68 2.75l-9.68 12.05-9.67-12.05c2.05-1.24 5.42-2.75 9.67-2.75m0-2c-7.26 0-12.1 3.96-12.58 4.32l12.57 15.66.01.02.01-.01 12.57-15.67c-.48-.36-5.32-4.32-12.58-4.32z"}]}

   {:name "Storage"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M4 40h40v-8h-40v8zm4-6h4v4h-4v-4zm-4-26v8h40v-8h-40zm8 6h-4v-4h4v4zm-8 14h40v-8h-40v8zm4-6h4v4h-4v-4z"}]}

   {:name "Usb"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M30 14v8h2v4h-6v-16h4l-6-8-6 8h4v16h-6v-4.14c1.41-.73 2.4-2.16 2.4-3.86 0-2.43-1.97-4.4-4.4-4.4-2.43 0-4.4 1.97-4.4 4.4 0 1.7.99 3.13 2.4 3.86v4.14c0 2.21 1.79 4 4 4h6v6.1c-1.42.73-2.4 2.19-2.4 3.9 0 2.43 1.97 4.4 4.4 4.4 2.43 0 4.4-1.97 4.4-4.4 0-1.71-.98-3.17-2.4-3.9v-6.1h6c2.21 0 4-1.79 4-4v-4h2v-8h-8z"}]}

   {:name "Wifi Lock"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M41 19c.56 0 1.09.08 1.63.16l5.37-7.16c-6.69-5.02-15-8-24-8s-17.31 2.98-24 8l24 32 7-9.33v-5.67c0-5.52 4.48-10 10-10zm5 13v-3c0-2.76-2.24-5-5-5s-5 2.24-5 5v3c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2v-8c0-1.1-.9-2-2-2zm-2 0h-6v-3c0-1.66 1.34-3 3-3s3 1.34 3 3v3z"}]}

   {:name "Wifi Tethering"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 22c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm12 4c0-6.63-5.37-12-12-12s-12 5.37-12 12c0 4.44 2.41 8.3 5.99 10.38l2.02-3.48c-2.39-1.39-4.01-3.94-4.01-6.9 0-4.42 3.58-8 8-8s8 3.58 8 8c0 2.96-1.62 5.51-4.01 6.89l2.02 3.48c3.58-2.07 5.99-5.93 5.99-10.37zm-12-20c-11.05 0-20 8.95-20 20 0 7.39 4.02 13.83 9.99 17.29l2-3.46c-4.77-2.76-7.99-7.92-7.99-13.83 0-8.84 7.16-16 16-16s16 7.16 16 16c0 5.91-3.22 11.07-7.99 13.84l2 3.46c5.97-3.47 9.99-9.91 9.99-17.3 0-11.05-8.96-20-20-20z"}]}])
