;; This work is licensed under CC BY 4.0.
;; The original source can be found here:
;; https://github.com/google/material-design-icons

(ns uxbox.builtins.library.icons.material.social
  (:require [uxbox.util.uuid :as uuid]))

(def +icons+
  [{:name "Cake"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M24 12c2.21 0 4-1.79 4-4 0-.75-.21-1.46-.57-2.06L24 0l-3.43 5.94C20.21 6.54 20 7.25 20 8c0 2.21 1.79 4 4 4zm9.19 19.97l-2.15-2.15-2.16 2.15c-2.61 2.61-7.17 2.61-9.78 0l-2.15-2.15-2.16 2.15C13.5 33.28 11.77 34 9.92 34c-1.45 0-2.8-.46-3.92-1.23V42c0 1.1.9 2 2 2h32c1.1 0 2-.9 2-2v-9.23c-1.12.77-2.46 1.23-3.92 1.23-1.85 0-3.58-.72-4.89-2.03zM36 18H26v-4h-4v4H12c-3.31 0-6 2.69-6 6v3.08C6 29.24 7.76 31 9.92 31c1.05 0 2.03-.41 2.77-1.15l4.28-4.27 4.27 4.26c1.48 1.48 4.06 1.48 5.54 0l4.28-4.26 4.27 4.26c.74.74 1.72 1.15 2.77 1.15 2.16 0 3.92-1.76 3.92-3.92V24c-.02-3.31-2.71-6-6.02-6z" :style {:stroke nil}}]}

   {:name "Domain"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M24 14V6H4v36h40V14H24zM12 38H8v-4h4v4zm0-8H8v-4h4v4zm0-8H8v-4h4v4zm0-8H8v-4h4v4zm8 24h-4v-4h4v4zm0-8h-4v-4h4v4zm0-8h-4v-4h4v4zm0-8h-4v-4h4v4zm20 24H24v-4h4v-4h-4v-4h4v-4h-4v-4h16v20zm-4-16h-4v4h4v-4zm0 8h-4v4h4v-4z" :style {:stroke nil}}]}

   {:name "Group"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M32 22c3.31 0 5.98-2.69 5.98-6s-2.67-6-5.98-6c-3.31 0-6 2.69-6 6s2.69 6 6 6zm-16 0c3.31 0 5.98-2.69 5.98-6s-2.67-6-5.98-6c-3.31 0-6 2.69-6 6s2.69 6 6 6zm0 4c-4.67 0-14 2.34-14 7v5h28v-5c0-4.66-9.33-7-14-7zm16 0c-.58 0-1.23.04-1.93.11C32.39 27.78 34 30.03 34 33v5h12v-5c0-4.66-9.33-7-14-7z" :style {:stroke nil}}]}

   {:name "Group Add"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M16 20h-6v-6H6v6H0v4h6v6h4v-6h6v-4zm20 2c3.31 0 5.98-2.69 5.98-6s-2.67-6-5.98-6c-.64 0-1.25.1-1.83.29 1.13 1.62 1.81 3.59 1.81 5.71s-.68 4.09-1.81 5.71c.58.19 1.19.29 1.83.29zm-10 0c3.31 0 5.98-2.69 5.98-6s-2.67-6-5.98-6c-3.31 0-6 2.69-6 6s2.69 6 6 6zm13.24 4.32C40.9 27.77 42 29.64 42 32v4h6v-4c0-3.08-4.75-4.97-8.76-5.68zM26 26c-4 0-12 2-12 6v4h24v-4c0-4-8-6-12-6z" :style {:stroke nil}}]}

   {:name "Location City"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M30 22V10l-6-6-6 6v4H6v28h36V22H30zM14 38h-4v-4h4v4zm0-8h-4v-4h4v4zm0-8h-4v-4h4v4zm12 16h-4v-4h4v4zm0-8h-4v-4h4v4zm0-8h-4v-4h4v4zm0-8h-4v-4h4v4zm12 24h-4v-4h4v4zm0-8h-4v-4h4v4z" :style {:stroke nil}}]}

   {:name "Mood"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M23.99 4C12.94 4 4 12.95 4 24s8.94 20 19.99 20C35.04 44 44 35.05 44 24S35.04 4 23.99 4zM24 40c-8.84 0-16-7.16-16-16S15.16 8 24 8s16 7.16 16 16-7.16 16-16 16zm7-18c1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3 1.34 3 3 3zm-14 0c1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3 1.34 3 3 3zm7 13c4.66 0 8.61-2.91 10.21-7H13.79c1.6 4.09 5.55 7 10.21 7z" :style {:stroke nil}}]}

   {:name "Notifications"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M23 44c2.21 0 4-1.79 4-4h-8c0 2.21 1.79 4 4 4zm13-12V21c0-6.15-4.27-11.28-10-12.64V7c0-1.66-1.34-3-3-3s-3 1.34-3 3v1.36C14.27 9.72 10 14.85 10 21v11l-4 4v2h34v-2l-4-4z" :style {:stroke nil}}]}

   {:name "Notifications None"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M23 44c2.21 0 4-1.79 4-4h-8c0 2.21 1.79 4 4 4zm13-12V21c0-6.15-4.27-11.28-10-12.64V7c0-1.66-1.34-3-3-3s-3 1.34-3 3v1.36C14.27 9.72 10 14.85 10 21v11l-4 4v2h34v-2l-4-4zm-4 2H14V21c0-4.97 4.03-9 9-9s9 4.03 9 9v13z" :style {:stroke nil}}]}

   {:name "Notifications Off"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M23 44c2.21 0 4-1.79 4-4h-8c0 2.21 1.79 4 4 4zm13-23c0-6.15-4.27-11.28-10-12.64V7c0-1.66-1.34-3-3-3s-3 1.34-3 3v1.36c-1.02.24-1.98.64-2.89 1.11L36 28.36V21zm-.54 17l4 4L42 39.45 8.55 6 6 8.55l5.84 5.84C10.68 16.32 10 18.58 10 21v11l-4 4v2h29.46z" :style {:stroke nil}}]}

   {:name "Notifications On"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M13.16 7.16L10.3 4.3C5.51 7.95 2.35 13.59 2.05 20h4c.31-5.3 3.04-9.94 7.11-12.84zM39.95 20h4c-.3-6.41-3.46-12.05-8.25-15.7l-2.85 2.85c4.06 2.91 6.79 7.55 7.1 12.85zM36 21c0-6.15-4.27-11.28-10-12.64V7c0-1.66-1.34-3-3-3s-3 1.34-3 3v1.36C14.27 9.72 10 14.85 10 21v11l-4 4v2h34v-2l-4-4V21zM23 44c.28 0 .55-.03.81-.08 1.3-.27 2.37-1.17 2.88-2.36.2-.48.31-1 .31-1.56h-8c0 2.21 1.79 4 4 4z" :style {:stroke nil}}]}

   {:name "Notifications Paused"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M23 44c2.21 0 4-1.79 4-4h-8c0 2.21 1.79 4 4 4zm13-12V21c0-6.15-4.27-11.28-10-12.64V7c0-1.66-1.34-3-3-3s-3 1.34-3 3v1.36C14.27 9.72 10 14.85 10 21v11l-4 4v2h34v-2l-4-4zm-8-12.4l-5.6 6.8H28V30H18v-3.6l5.6-6.8H18V16h10v3.6z" :style {:stroke nil}}]}

   {:name "Pages"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M6 10v12h10l-2-8 8 2V6H10c-2.21 0-4 1.79-4 4zm10 16H6v12c0 2.21 1.79 4 4 4h12V32l-8 2 2-8zm18 8l-8-2v10h12c2.21 0 4-1.79 4-4V26H32l2 8zm4-28H26v10l8-2-2 8h10V10c0-2.21-1.79-4-4-4z" :style {:stroke nil}}]}

   {:name "Party Mode"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M40 8h-6.34L30 4H18l-3.66 4H8c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4V12c0-2.21-1.79-4-4-4zm-16 6c3.26 0 6.13 1.59 7.96 4H24c-3.31 0-6 2.69-6 6 0 .71.14 1.37.37 2H14.2c-.13-.65-.2-1.31-.2-2 0-5.52 4.48-10 10-10zm0 20c-3.26 0-6.13-1.58-7.95-4H24c3.31 0 6-2.69 6-6 0-.7-.14-1.37-.37-2h4.17c.13.65.2 1.31.2 2 0 5.52-4.48 10-10 10z" :style {:stroke nil}}]}

   {:name "People"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M32 22c3.31 0 5.98-2.69 5.98-6s-2.67-6-5.98-6c-3.31 0-6 2.69-6 6s2.69 6 6 6zm-16 0c3.31 0 5.98-2.69 5.98-6s-2.67-6-5.98-6c-3.31 0-6 2.69-6 6s2.69 6 6 6zm0 4c-4.67 0-14 2.34-14 7v5h28v-5c0-4.66-9.33-7-14-7zm16 0c-.58 0-1.23.04-1.93.11C32.39 27.78 34 30.03 34 33v5h12v-5c0-4.66-9.33-7-14-7z" :style {:stroke nil}}]}

   {:name "People Outline"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M33 26c-2.41 0-6.15.67-9 2.01-2.85-1.34-6.59-2.01-9-2.01-4.33 0-13 2.17-13 6.5V38h44v-5.5c0-4.33-8.67-6.5-13-6.5zm-8 9H5v-2.5c0-1.07 5.12-3.5 10-3.5s10 2.43 10 3.5V35zm18 0H28v-2.5c0-.91-.4-1.72-1.04-2.44C28.73 29.46 30.89 29 33 29c4.88 0 10 2.43 10 3.5V35zM15 24c3.87 0 7-3.14 7-7s-3.13-7-7-7c-3.86 0-7 3.14-7 7s3.14 7 7 7zm0-11c2.21 0 4 1.79 4 4s-1.79 4-4 4-4-1.79-4-4 1.79-4 4-4zm18 11c3.87 0 7-3.14 7-7s-3.13-7-7-7c-3.86 0-7 3.14-7 7s3.14 7 7 7zm0-11c2.21 0 4 1.79 4 4s-1.79 4-4 4-4-1.79-4-4 1.79-4 4-4z" :style {:stroke nil}}]}

   {:name "Person"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M24 24c4.42 0 8-3.59 8-8 0-4.42-3.58-8-8-8s-8 3.58-8 8c0 4.41 3.58 8 8 8zm0 4c-5.33 0-16 2.67-16 8v4h32v-4c0-5.33-10.67-8-16-8z" :style {:stroke nil}}]}

   {:name "Person Add"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M30 24c4.42 0 8-3.59 8-8 0-4.42-3.58-8-8-8s-8 3.58-8 8c0 4.41 3.58 8 8 8zm-18-4v-6H8v6H2v4h6v6h4v-6h6v-4h-6zm18 8c-5.33 0-16 2.67-16 8v4h32v-4c0-5.33-10.67-8-16-8z" :style {:stroke nil}}]}

   {:name "Person Outline"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M24 11.8c2.32 0 4.2 1.88 4.2 4.2s-1.88 4.2-4.2 4.2-4.2-1.88-4.2-4.2 1.88-4.2 4.2-4.2m0 18c5.95 0 12.2 2.91 12.2 4.2v2.2H11.8V34c0-1.29 6.25-4.2 12.2-4.2M24 8c-4.42 0-8 3.58-8 8 0 4.41 3.58 8 8 8s8-3.59 8-8c0-4.42-3.58-8-8-8zm0 18c-5.33 0-16 2.67-16 8v6h32v-6c0-5.33-10.67-8-16-8z" :style {:stroke nil}}]}

   {:name "Plus One"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M20 16h-4v8H8v4h8v8h4v-8h8v-4h-8zm9-3.84v3.64l5-1V36h4V10z" :style {:stroke nil}}]}

   {:name "Poll"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M38 6H10c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4V10c0-2.21-1.79-4-4-4zM18 34h-4V20h4v14zm8 0h-4V14h4v20zm8 0h-4v-8h4v8z" :style {:stroke nil}}]}

   {:name "Public"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M24 4C12.95 4 4 12.95 4 24s8.95 20 20 20 20-8.95 20-20S35.05 4 24 4zm-2 35.86C14.11 38.88 8 32.16 8 24c0-1.23.15-2.43.42-3.58L18 30v2c0 2.21 1.79 4 4 4v3.86zm13.79-5.07C35.28 33.17 33.78 32 32 32h-2v-6c0-1.1-.9-2-2-2H16v-4h4c1.1 0 2-.9 2-2v-4h4c2.21 0 4-1.79 4-4v-.83c5.86 2.37 10 8.11 10 14.83 0 4.16-1.6 7.94-4.21 10.79z" :style {:stroke nil}}]}

   {:name "School"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M10 26.36v8L24 42l14-7.64v-8L24 34l-14-7.64zM24 6L2 18l22 12 18-9.82V34h4V18L24 6z" :style {:stroke nil}}]}

   {:name "Share"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M36 32.17c-1.52 0-2.89.59-3.93 1.54L17.82 25.4c.11-.45.18-.92.18-1.4s-.07-.95-.18-1.4l14.1-8.23c1.07 1 2.5 1.62 4.08 1.62 3.31 0 6-2.69 6-6s-2.69-6-6-6-6 2.69-6 6c0 .48.07.95.18 1.4l-14.1 8.23c-1.07-1-2.5-1.62-4.08-1.62-3.31 0-6 2.69-6 6s2.69 6 6 6c1.58 0 3.01-.62 4.08-1.62l14.25 8.31c-.1.42-.16.86-.16 1.31 0 3.22 2.61 5.83 5.83 5.83s5.83-2.61 5.83-5.83-2.61-5.83-5.83-5.83z" :style {:stroke nil}}]}

   {:name "Whats Hot"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:d "M27 1.34s1.48 5.3 1.48 9.6c0 4.12-2.7 7.47-6.83 7.47s-7.25-3.34-7.25-7.47l.05-.72C10.43 15.03 8 21.23 8 28c0 8.84 7.16 16 16 16s16-7.16 16-16c0-10.79-5.19-20.41-13-26.66zM23.42 38c-3.56 0-6.45-2.81-6.45-6.28 0-3.25 2.09-5.53 5.63-6.24s7.2-2.41 9.23-5.15c.78 2.58 1.19 5.3 1.19 8.07 0 5.29-4.3 9.6-9.6 9.6z" :style {:stroke nil}}]}])
