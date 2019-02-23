;; This work is licensed under CC BY 4.0.
;; The original source can be found here:
;; https://github.com/google/material-design-icons

(ns uxbox.builtins.library.icons.material.editor
  (:require [uxbox.util.uuid :as uuid]))

(def +icons+
  [{:name "Attach File"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M33 12v23c0 4.42-3.58 8-8 8s-8-3.58-8-8v-25c0-2.76 2.24-5 5-5s5 2.24 5 5v21c0 1.1-.89 2-2 2-1.11 0-2-.9-2-2v-19h-3v19c0 2.76 2.24 5 5 5s5-2.24 5-5v-21c0-4.42-3.58-8-8-8s-8 3.58-8 8v25c0 6.08 4.93 11 11 11s11-4.92 11-11v-23h-3z"}]}

   {:name "Attach Money"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M23.6 21.8c-4.54-1.18-6-2.39-6-4.29 0-2.18 2.01-3.71 5.4-3.71 3.56 0 4.88 1.7 5 4.2h4.42c-.13-3.45-2.24-6.59-6.42-7.62v-4.38h-6v4.32c-3.88.85-7 3.35-7 7.22 0 4.62 3.83 6.92 9.4 8.26 5.01 1.2 6 2.95 6 4.83 0 1.37-.97 3.57-5.4 3.57-4.12 0-5.75-1.85-5.96-4.2h-4.41c.25 4.38 3.52 6.83 7.37 7.66v4.34h6v-4.3c3.89-.75 7-3 7-7.11 0-5.66-4.86-7.6-9.4-8.79z"}]}

   {:name "Border All"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6 6v36h36v-36h-36zm16 32h-12v-12h12v12zm0-16h-12v-12h12v12zm16 16h-12v-12h12v12zm0-16h-12v-12h12v12z"}]}

   {:name "Border Bottom"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M18 22h-4v4h4v-4zm8 8h-4v4h4v-4zm-8-24h-4v4h4v-4zm8 16h-4v4h4v-4zm-16-16h-4v4h4v-4zm16 8h-4v4h4v-4zm8 8h-4v4h4v-4zm-8-16h-4v4h4v-4zm8 0h-4v4h4v-4zm4 20h4v-4h-4v4zm0 8h4v-4h-4v4zm-28-20h-4v4h4v-4zm28-8v4h4v-4h-4zm0 12h4v-4h-4v4zm-28 4h-4v4h4v-4zm-4 20h36v-4h-36v4zm4-12h-4v4h4v-4z"}]}

   {:name "Border Clear"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 10h4v-4h-4v4zm0 16h4v-4h-4v4zm0 16h4v-4h-4v4zm8-8h4v-4h-4v4zm0 8h4v-4h-4v4zm-16 0h4v-4h-4v4zm0-8h4v-4h-4v4zm0-8h4v-4h-4v4zm0-8h4v-4h-4v4zm0-8h4v-4h-4v4zm16 16h4v-4h-4v4zm16 8h4v-4h-4v4zm0-8h4v-4h-4v4zm0 16h4v-4h-4v4zm0-24h4v-4h-4v4zm-16 0h4v-4h-4v4zm16-12v4h4v-4h-4zm-16 4h4v-4h-4v4zm8 32h4v-4h-4v4zm0-16h4v-4h-4v4zm0-16h4v-4h-4v4z"}]}

   {:name "Border Color"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:g
           [:path
            {:style {:stroke nil}
             :d
             "M35.5 14l-7.5-7.5-20 20v7.5h7.5l20-20zm5.91-5.91c.78-.78.78-2.05 0-2.83l-4.67-4.67c-.78-.78-2.05-.78-2.83 0l-3.91 3.91 7.5 7.5 3.91-3.91z"}]
           [:path
            {:style {:stroke nil}
             :d "M0 40h48v8h-48z"
             :fill-opacity ".36"}]]}

   {:name "Border Horizontal"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6 42h4v-4h-4v4zm4-28h-4v4h4v-4zm-4 20h4v-4h-4v4zm8 8h4v-4h-4v4zm-4-36h-4v4h4v-4zm8 0h-4v4h4v-4zm16 0h-4v4h4v-4zm-8 8h-4v4h4v-4zm0-8h-4v4h4v-4zm12 28h4v-4h-4v4zm-16 8h4v-4h-4v4zm-16-16h36v-4h-36v4zm32-20v4h4v-4h-4zm0 12h4v-4h-4v4zm-16 16h4v-4h-4v4zm8 8h4v-4h-4v4zm8 0h4v-4h-4v4z"}]}

   {:name "Border Inner"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6 42h4v-4h-4v4zm8 0h4v-4h-4v4zm-4-28h-4v4h4v-4zm-4 20h4v-4h-4v4zm12-28h-4v4h4v-4zm-8 0h-4v4h4v-4zm24 0h-4v4h4v-4zm4 12h4v-4h-4v4zm0-12v4h4v-4h-4zm-8 36h4v-4h-4v4zm-4-36h-4v16h-16v4h16v16h4v-16h16v-4h-16v-16zm12 36h4v-4h-4v4zm0-8h4v-4h-4v4z"}]}

   {:name "Border Left"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M22 42h4v-4h-4v4zm0-8h4v-4h-4v4zm0-24h4v-4h-4v4zm0 8h4v-4h-4v4zm0 8h4v-4h-4v4zm-8 16h4v-4h-4v4zm0-32h4v-4h-4v4zm0 16h4v-4h-4v4zm-8 16h4v-36h-4v36zm32-24h4v-4h-4v4zm-8 24h4v-4h-4v4zm8-8h4v-4h-4v4zm0-28v4h4v-4h-4zm0 20h4v-4h-4v4zm0 16h4v-4h-4v4zm-8-16h4v-4h-4v4zm0-16h4v-4h-4v4z"}]}

   {:name "Border Outer"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M26 14h-4v4h4v-4zm0 8h-4v4h4v-4zm8 0h-4v4h4v-4zm-28-16v36h36v-36h-36zm32 32h-28v-28h28v28zm-12-8h-4v4h4v-4zm-8-8h-4v4h4v-4z"}]}

   {:name "Border Right"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 42h4v-4h-4v4zm-8-32h4v-4h-4v4zm8 0h4v-4h-4v4zm0 16h4v-4h-4v4zm-8 16h4v-4h-4v4zm16 0h4v-4h-4v4zm-16-16h4v-4h-4v4zm0 8h4v-4h-4v4zm0-16h4v-4h-4v4zm16 16h4v-4h-4v4zm8-8h4v-4h-4v4zm8-20v36h4v-36h-4zm-8 36h4v-4h-4v4zm0-32h4v-4h-4v4zm-8 16h4v-4h-4v4zm0-16h4v-4h-4v4zm0 8h4v-4h-4v4z"}]}

   {:name "Border Style"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M30 42h4v-4h-4v4zm8 0h4v-4h-4v4zm-24 0h4v-4h-4v4zm8 0h4v-4h-4v4zm16-8h4v-4h-4v4zm0-8h4v-4h-4v4zm-32-20v36h4v-32h32v-4h-36zm32 12h4v-4h-4v4z"}]}

   {:name "Border Top"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 42h4v-4h-4v4zm0-16h4v-4h-4v4zm8 0h4v-4h-4v4zm0 16h4v-4h-4v4zm-16-8h4v-4h-4v4zm0 8h4v-4h-4v4zm0-16h4v-4h-4v4zm0-8h4v-4h-4v4zm16 16h4v-4h-4v4zm16-16h4v-4h-4v4zm0 8h4v-4h-4v4zm-32-20v4h36v-4h-36zm32 28h4v-4h-4v4zm-8 8h4v-4h-4v4zm-8-24h4v-4h-4v4zm16 24h4v-4h-4v4zm-8-16h4v-4h-4v4z"}]}

   {:name "Border Vertical"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6 18h4v-4h-4v4zm0-8h4v-4h-4v4zm8 32h4v-4h-4v4zm0-16h4v-4h-4v4zm-8 0h4v-4h-4v4zm0 16h4v-4h-4v4zm0-8h4v-4h-4v4zm8-24h4v-4h-4v4zm24 24h4v-4h-4v4zm-16 8h4v-36h-4v36zm16 0h4v-4h-4v4zm0-16h4v-4h-4v4zm0-20v4h4v-4h-4zm0 12h4v-4h-4v4zm-8-8h4v-4h-4v4zm0 32h4v-4h-4v4zm0-16h4v-4h-4v4z"}]}

   {:name "Format Align Center"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 30v4h20v-4h-20zm-8 12h36v-4h-36v4zm0-16h36v-4h-36v4zm8-12v4h20v-4h-20zm-8-8v4h36v-4h-36z"}]}

   {:name "Format Align Justify"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6 42h36v-4h-36v4zm0-8h36v-4h-36v4zm0-8h36v-4h-36v4zm0-8h36v-4h-36v4zm0-12v4h36v-4h-36z"}]}

   {:name "Format Align Left"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M30 30h-24v4h24v-4zm0-16h-24v4h24v-4zm-24 12h36v-4h-36v4zm0 16h36v-4h-36v4zm0-36v4h36v-4h-36z"}]}

   {:name "Format Align Right"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6 42h36v-4h-36v4zm12-8h24v-4h-24v4zm-12-8h36v-4h-36v4zm12-8h24v-4h-24v4zm-12-12v4h36v-4h-36z"}]}

   {:name "Format Bold"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M31.2 21.58c1.93-1.35 3.3-3.53 3.3-5.58 0-4.51-3.49-8-8-8h-12.5v28h14.08c4.19 0 7.42-3.4 7.42-7.58 0-3.04-1.73-5.63-4.3-6.84zm-11.2-8.58h6c1.66 0 3 1.34 3 3s-1.34 3-3 3h-6v-6zm7 18h-7v-6h7c1.66 0 3 1.34 3 3s-1.34 3-3 3z"}]}

   {:name "Format Clear"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6.54 10l-2.54 2.55 13.94 13.94-4.94 11.51h6l3.14-7.32 11.32 11.32 2.54-2.55-28.91-28.9-.55-.55zm5.46 0v.36l5.64 5.64h4.79l-1.44 3.35 4.2 4.2 3.24-7.55h11.57v-6h-28z"}]}

   {:name "Format Color Fill"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M33.12 17.88l-17.88-17.88-2.83 2.83 4.76 4.76-10.29 10.29c-1.17 1.17-1.17 3.07 0 4.24l11 11c.58.59 1.35.88 2.12.88s1.54-.29 2.12-.88l11-11c1.17-1.17 1.17-3.07 0-4.24zm-22.71 2.12l9.59-9.58 9.59 9.58h-19.18zm27.59 3s-4 4.33-4 7c0 2.21 1.79 4 4 4s4-1.79 4-4c0-2.67-4-7-4-7z"}]}

   {:name "Format Color Reset"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M36 28c0-8-12-21.6-12-21.6s-2.66 3.02-5.47 7.04l17.17 17.17c.19-.84.3-1.71.3-2.61zm-1.76 6.24l-9.24-9.24-14.46-14.46-2.54 2.55 6.64 6.64c-1.53 2.92-2.64 5.85-2.64 8.27 0 6.63 5.37 12 12 12 3.04 0 5.8-1.14 7.91-3l5.27 5.27 2.54-2.55-5.48-5.48z"}]}

   {:name "Format Color Text"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M22 6l-11 28h4.5l2.25-6h12.5l2.25 6h4.5l-11-28h-4zm-2.75 18l4.75-12.67 4.75 12.67h-9.5z"}]}

   {:name "Format Indent Decrease"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M22 34h20v-4h-20v4zm-16-10l8 8v-16l-8 8zm0 18h36v-4h-36v4zm0-36v4h36v-4h-36zm16 12h20v-4h-20v4zm0 8h20v-4h-20v4z"}]}

   {:name "Format Italic"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M20 8v6h4.43l-6.86 16h-5.57v6h16v-6h-4.43l6.86-16h5.57v-6z"}]}

   {:name "Format Line Spacing"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M12 14h5l-7-7-7 7h5v20h-5l7 7 7-7h-5v-20zm8-4v4h24v-4h-24zm0 28h24v-4h-24v4zm0-12h24v-4h-24v4z"}]}

   {:name "Format List Bulleted"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M8 21c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3zm0-12c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3zm0 24.33c-1.47 0-2.67 1.19-2.67 2.67s1.2 2.67 2.67 2.67 2.67-1.19 2.67-2.67-1.2-2.67-2.67-2.67zm6 4.67h28v-4h-28v4zm0-12h28v-4h-28v4zm0-16v4h28v-4h-28z"}]}

   {:name "Format List Numbered"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M4 34h4v1h-2v2h2v1h-4v2h6v-8h-6v2zm2-18h2v-8h-4v2h2v6zm-2 6h3.6l-3.6 4.2v1.8h6v-2h-3.6l3.6-4.2v-1.8h-6v2zm10-12v4h28v-4h-28zm0 28h28v-4h-28v4zm0-12h28v-4h-28v4z"}]}

   {:name "Format Paint"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M36 8v-2c0-1.1-.9-2-2-2h-24c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h24c1.1 0 2-.9 2-2v-2h2v8h-20v22c0 1.1.9 2 2 2h4c1.1 0 2-.9 2-2v-18h16v-16h-6z"}]}

   {:name "Format Quote"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M12 34h6l4-8v-12h-12v12h6zm16 0h6l4-8v-12h-12v12h6z"}]}

   {:name "Format Size"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M18 8v6h10v24h6v-24h10v-6h-26zm-12 16h6v14h6v-14h6v-6h-18v6z"}]}

   {:name "Format Strikethrough"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M20 38h8v-6h-8v6zm-10-30v6h10v6h8v-6h10v-6h-28zm-4 20h36v-4h-36v4z"}]}

   {:name "Format Text Direction l To r"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M36 8h-24v4l13 12-13 12v4h24v-6h-14l10-10-10-10h14z"}]}

   {:name "Format Underline"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 34c6.63 0 12-5.37 12-12v-16h-5v16c0 3.87-3.13 7-7 7s-7-3.13-7-7v-16h-5v16c0 6.63 5.37 12 12 12zm-14 4v4h28v-4h-28z"}]}

   {:name "Functions"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M20 20v10h4v-22h4v22h4v-22h4v-4h-16c-4.42 0-8 3.58-8 8s3.58 8 8 8zm-4 14v-6l-8 8 8 8v-6h24v-4h-24z"}]}

   {:name "Insert Chart"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 6h-28c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4v-28c0-2.21-1.79-4-4-4zm-20 28h-4v-14h4v14zm8 0h-4v-20h4v20zm8 0h-4v-8h4v8z"}]}

   {:name "Insert Comment"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 4h-32c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h28l8 8v-36c0-2.21-1.79-4-4-4zm-4 24h-24v-4h24v4zm0-6h-24v-4h24v4zm0-6h-24v-4h24v4z"}]}

   {:name "Insert Drive File"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M12 4c-2.21 0-3.98 1.79-3.98 4l-.02 32c0 2.21 1.77 4 3.98 4h24.02c2.21 0 4-1.79 4-4v-24l-12-12h-16zm14 14v-11l11 11h-11z"}]}

   {:name "Insert Emoticon"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M23.99 4c-11.05 0-19.99 8.95-19.99 20s8.94 20 19.99 20c11.05 0 20.01-8.95 20.01-20s-8.96-20-20.01-20zm.01 36c-8.84 0-16-7.16-16-16s7.16-16 16-16 16 7.16 16 16-7.16 16-16 16zm7-18c1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3 1.34 3 3 3zm-14 0c1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3 1.34 3 3 3zm7 13c4.66 0 8.61-2.91 10.21-7h-20.42c1.6 4.09 5.55 7 10.21 7z"}]}

   {:name "Insert Invitation"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 24h-10v10h10v-10zm-2-22v4h-16v-4h-4v4h-2c-2.21 0-3.98 1.79-3.98 4l-.02 28c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4v-28c0-2.21-1.79-4-4-4h-2v-4h-4zm6 36h-28v-22h28v22z"}]}

   {:name "Insert Link"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M7.8 24c0-3.42 2.78-6.2 6.2-6.2h8v-3.8h-8c-5.52 0-10 4.48-10 10s4.48 10 10 10h8v-3.8h-8c-3.42 0-6.2-2.78-6.2-6.2zm8.2 2h16v-4h-16v4zm18-12h-8v3.8h8c3.42 0 6.2 2.78 6.2 6.2s-2.78 6.2-6.2 6.2h-8v3.8h8c5.52 0 10-4.48 10-10s-4.48-10-10-10z"}]}

   {:name "Insert Photo"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M42 38v-28c0-2.21-1.79-4-4-4h-28c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4zm-25-11l5 6.01 7-9.01 9 12h-28l7-9z"}]}

   {:name "Merge Type"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 40.83l2.83-2.83-6.83-6.83-2.83 2.83 6.83 6.83zm-19-24.83h7v11.17l-10.83 10.83 2.83 2.83 12-12v-12.83h7l-9-9-9 9z"}]}

   {:name "Mode Comment"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M43.98 8c0-2.21-1.77-4-3.98-4h-32c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h28l8 8-.02-36z"}]}

   {:name "Publish"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M10 8v4h28v-4h-28zm0 20h8v12h12v-12h8l-14-14-14 14z"}]}

   {:name "Vertical Align Bottom"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M32 26h-6v-20h-4v20h-6l8 8 8-8zm-24 12v4h32v-4h-32z"}]}

   {:name "Vertical Align Center"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M16 38h6v8h4v-8h6l-8-8-8 8zm16-28h-6v-8h-4v8h-6l8 8 8-8zm-24 12v4h32v-4h-32z"}]}

   {:name "Vertical Align Top"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M16 22h6v20h4v-20h6l-8-8-8 8zm-8-16v4h32v-4h-32z"}]}

   {:name "Wrap Text"
    :id (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M8 38h12v-4h-12v4zm32-28h-32v4h32v-4zm-6 12h-26v4h26.5c2.21 0 4 1.79 4 4s-1.79 4-4 4h-4.5v-4l-6 6 6 6v-4h4c4.41 0 8-3.59 8-8s-3.59-8-8-8z"}]}])
