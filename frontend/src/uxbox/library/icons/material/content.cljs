;; This work is licensed under CC BY 4.0.
;; The original source can be found here:
;; https://github.com/google/material-design-icons

(ns uxbox.library.icons.material.content)

(def +icons+
  [{:name "Add"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M38 26h-12v12h-4v-12h-12v-4h12v-12h4v12h12v4z"}]}

   {:name "Add Box"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 6h-28c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4v-28c0-2.21-1.79-4-4-4zm-4 20h-8v8h-4v-8h-8v-4h8v-8h4v8h8v4z"}]}

   {:name "Add Circle"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4c-11.05 0-20 8.95-20 20s8.95 20 20 20 20-8.95 20-20-8.95-20-20-20zm10 22h-8v8h-4v-8h-8v-4h8v-8h4v8h8v4z"}]}

   {:name "Add Circle Outline"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M26 14h-4v8h-8v4h8v8h4v-8h8v-4h-8v-8zm-2-10c-11.05 0-20 8.95-20 20s8.95 20 20 20 20-8.95 20-20-8.95-20-20-20zm0 36c-8.82 0-16-7.18-16-16s7.18-16 16-16 16 7.18 16 16-7.18 16-16 16z"}]}

   {:name "Archive"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M41.09 10.45l-2.77-3.36c-.56-.66-1.39-1.09-2.32-1.09h-24c-.93 0-1.76.43-2.31 1.09l-2.77 3.36c-.58.7-.92 1.58-.92 2.55v25c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4v-25c0-.97-.34-1.85-.91-2.55zm-17.09 24.55l-11-11h7v-4h8v4h7l-11 11zm-13.75-25l1.63-2h24l1.87 2h-27.5z"}]}

   {:name "Archive"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M44 6h-30c-1.38 0-2.47.7-3.19 1.76l-10.81 16.23 10.81 16.23c.72 1.06 1.81 1.78 3.19 1.78h30c2.21 0 4-1.79 4-4v-28c0-2.21-1.79-4-4-4zm-6 25.17l-2.83 2.83-7.17-7.17-7.17 7.17-2.83-2.83 7.17-7.17-7.17-7.17 2.83-2.83 7.17 7.17 7.17-7.17 2.83 2.83-7.17 7.17 7.17 7.17z"}]}

   {:name "Block"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4c-11.05 0-20 8.95-20 20s8.95 20 20 20 20-8.95 20-20-8.95-20-20-20zm-16 20c0-8.84 7.16-16 16-16 3.7 0 7.09 1.27 9.8 3.37l-22.43 22.43c-2.1-2.71-3.37-6.1-3.37-9.8zm16 16c-3.7 0-7.09-1.27-9.8-3.37l22.43-22.43c2.1 2.71 3.37 6.1 3.37 9.8 0 8.84-7.16 16-16 16z"}]}

   {:name "Clear"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 12.83l-2.83-2.83-11.17 11.17-11.17-11.17-2.83 2.83 11.17 11.17-11.17 11.17 2.83 2.83 11.17-11.17 11.17 11.17 2.83-2.83-11.17-11.17z"}]}

   {:name "Content Copy"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M32 2h-24c-2.21 0-4 1.79-4 4v28h4v-28h24v-4zm6 8h-22c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h22c2.21 0 4-1.79 4-4v-28c0-2.21-1.79-4-4-4zm0 32h-22v-28h22v28z"}]}

   {:name "Content Cut"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M19.28 15.28c.45-1 .72-2.11.72-3.28 0-4.42-3.58-8-8-8s-8 3.58-8 8 3.58 8 8 8c1.17 0 2.28-.27 3.28-.72l4.72 4.72-4.72 4.72c-1-.45-2.11-.72-3.28-.72-4.42 0-8 3.58-8 8s3.58 8 8 8 8-3.58 8-8c0-1.17-.27-2.28-.72-3.28l4.72-4.72 14 14h6v-2l-24.72-24.72zm-7.28.72c-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4-1.79 4-4 4zm0 24c-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4-1.79 4-4 4zm12-15c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm14-19l-12 12 4 4 14-14v-2z"}]}

   {:name "Content Paste"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 4h-8.37c-.82-2.32-3.02-4-5.63-4s-4.81 1.68-5.63 4h-8.37c-2.21 0-4 1.79-4 4v32c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4v-32c0-2.21-1.79-4-4-4zm-14 0c1.1 0 2 .89 2 2s-.9 2-2 2-2-.89-2-2 .9-2 2-2zm14 36h-28v-32h4v6h20v-6h4v32z"}]}

   {:name "Create"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6 34.5v7.5h7.5l22.13-22.13-7.5-7.5-22.13 22.13zm35.41-20.41c.78-.78.78-2.05 0-2.83l-4.67-4.67c-.78-.78-2.05-.78-2.83 0l-3.66 3.66 7.5 7.5 3.66-3.66z"}]}

   {:name "Drafts"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M43.98 16c0-1.44-.75-2.69-1.88-3.4l-18.1-10.6-18.1 10.6c-1.13.71-1.9 1.96-1.9 3.4v20c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4l-.02-20zm-19.98 10l-16.52-10.33 16.52-9.67 16.52 9.67-16.52 10.33z"}]}

   {:name "Filter List"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M20 36h8v-4h-8v4zm-14-24v4h36v-4h-36zm6 14h24v-4h-24v4z"}]}

   {:name "Flag"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M28.8 12l-.8-4h-18v34h4v-14h11.2l.8 4h14v-20z"}]}

   {:name "Forward"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M24 16v-8l16 16-16 16v-8h-16v-16z"}]}

   {:name "Gesture"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M9.19 13.77c1.4-1.43 2.8-2.71 3.43-2.45.99.41-.02 2.08-.6 3.05-.5.84-5.72 7.77-5.72 12.62 0 2.56.95 4.68 2.69 5.96 1.5 1.11 3.47 1.45 5.28.92 2.14-.63 3.9-2.79 6.12-5.53 2.42-2.98 5.66-6.88 8.16-6.88 3.26 0 3.3 2.02 3.52 3.59-7.58 1.3-10.77 7.35-10.77 10.76s2.88 6.19 6.41 6.19c3.25 0 8.59-2.66 9.38-12.2h4.91v-5h-4.94c-.3-3.3-2.18-8.39-8.06-8.39-4.5 0-8.37 3.82-9.87 5.69-1.16 1.45-4.11 4.95-4.57 5.45-.51.59-1.35 1.68-2.23 1.68-.89 0-1.43-1.67-.73-3.85.7-2.19 2.8-5.72 3.7-7.03 1.57-2.28 2.59-3.85 2.59-6.56 0-4.4-3.28-5.78-5.02-5.78-2.64 0-4.94 2-5.45 2.51-.71.72-1.31 1.31-1.75 1.85l3.52 3.4zm18.58 23.34c-.62 0-1.47-.52-1.47-1.45 0-1.2 1.45-4.4 5.75-5.53-.62 5.39-2.88 6.98-4.28 6.98z"}]}

   {:name "Inbox"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 6h-28.02c-2.21 0-3.96 1.79-3.96 4l-.02 28c0 2.21 1.77 4 3.98 4h28.02c2.21 0 4-1.79 4-4v-28c0-2.21-1.79-4-4-4zm0 24h-8c0 3.31-2.69 6-6 6s-6-2.69-6-6h-8.02v-20h28.02v20zm-6-10h-4v-6h-8v6h-4l8 8 8-8z"}]}

   {:name "Link"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M7.8 24c0-3.42 2.78-6.2 6.2-6.2h8v-3.8h-8c-5.52 0-10 4.48-10 10s4.48 10 10 10h8v-3.8h-8c-3.42 0-6.2-2.78-6.2-6.2zm8.2 2h16v-4h-16v4zm18-12h-8v3.8h8c3.42 0 6.2 2.78 6.2 6.2s-2.78 6.2-6.2 6.2h-8v3.8h8c5.52 0 10-4.48 10-10s-4.48-10-10-10z"}]}

   {:name "Mail"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 8h-32c-2.21 0-3.98 1.79-3.98 4l-.02 24c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4v-24c0-2.21-1.79-4-4-4zm0 8l-16 10-16-10v-4l16 10 16-10v4z"}]}

   {:name "Redo"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M36.79 21.2c-3.68-3.23-8.5-5.2-13.79-5.2-9.3 0-17.17 6.06-19.92 14.44l4.73 1.56c2.1-6.39 8.1-11 15.19-11 3.91 0 7.46 1.44 10.23 3.77l-7.23 7.23h18v-18l-7.21 7.2z"}]}

   {:name "Remove"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path {:style {:stroke nil}
                  :d "M38 26h-28v-4h28v4z"}]}

   {:name "Remove Outline"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4c-11.05 0-20 8.95-20 20s8.95 20 20 20 20-8.95 20-20-8.95-20-20-20zm10 22h-20v-4h20v4z"}]}

   {:name "Remove Outline Circle"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 22v4h20v-4h-20zm10-18c-11.05 0-20 8.95-20 20s8.95 20 20 20 20-8.95 20-20-8.95-20-20-20zm0 36c-8.82 0-16-7.18-16-16s7.18-16 16-16 16 7.18 16 16-7.18 16-16 16z"}]}

   {:name "Reply"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M20 18v-8l-14 14 14 14v-8.2c10 0 17 3.2 22 10.2-2-10-8-20-22-22z"}]}

   {:name "Reply All"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 16v-6l-14 14 14 14v-6l-8-8 8-8zm12 2v-8l-14 14 14 14v-8.2c10 0 17 3.2 22 10.2-2-10-8-20-22-22z"}]}

   {:name "Report"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M31.46 6h-14.92l-10.54 10.54v14.91l10.54 10.55h14.91l10.55-10.54v-14.92l-10.54-10.54zm-7.46 28.6c-1.43 0-2.6-1.16-2.6-2.6 0-1.43 1.17-2.6 2.6-2.6 1.43 0 2.6 1.16 2.6 2.6 0 1.44-1.17 2.6-2.6 2.6zm2-8.6h-4v-12h4v12z"}]}

   {:name "Save"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 6h-24c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4v-24l-8-8zm-10 32c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm6-20h-20v-8h20v8z"}]}

   {:name "Select All"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6 10h4v-4c-2.21 0-4 1.79-4 4zm0 16h4v-4h-4v4zm8 16h4v-4h-4v4zm-8-24h4v-4h-4v4zm20-12h-4v4h4v-4zm12 0v4h4c0-2.21-1.79-4-4-4zm-28 36v-4h-4c0 2.21 1.79 4 4 4zm-4-8h4v-4h-4v4zm12-28h-4v4h4v-4zm4 36h4v-4h-4v4zm16-16h4v-4h-4v4zm0 16c2.21 0 4-1.79 4-4h-4v4zm0-24h4v-4h-4v4zm0 16h4v-4h-4v4zm-8 8h4v-4h-4v4zm0-32h4v-4h-4v4zm-16 24h20v-20h-20v20zm4-16h12v12h-12v-12z"}]}

   {:name "Send"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M4.02 42l41.98-18-41.98-18-.02 14 30 4-30 4z"}]}

   {:name "Sort"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M6 36h12v-4h-12v4zm0-24v4h36v-4h-36zm0 14h24v-4h-24v4z"}]}

   {:name "Text Format"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M10 34v4h28v-4h-28zm9-8.4h10l1.8 4.4h4.2l-9.5-22h-3l-9.5 22h4.2l1.8-4.4zm5-13.64l3.74 10.04h-7.48l3.74-10.04z"}]}

   {:name "Undo"
    :id (gensym "icon")
    :type :builtin/icon
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M25 16c-5.29 0-10.11 1.97-13.8 5.2l-7.2-7.2v18h18l-7.23-7.23c2.77-2.33 6.32-3.77 10.23-3.77 7.09 0 13.09 4.61 15.19 11l4.73-1.56c-2.75-8.38-10.62-14.44-19.92-14.44z"}]}])
