;; This work is licensed under CC BY 4.0.
;; The original source can be found here:
;; https://github.com/google/material-design-icons

(ns uxbox.builtins.library.icons.material.av
  (:require [uxbox.util.uuid :as uuid]))

(def +icons+
  [{:name "Album"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4C12.95 4 4 12.95 4 24s8.95 20 20 20 20-8.95 20-20S35.05 4 24 4zm0 29c-4.97 0-9-4.03-9-9s4.03-9 9-9 9 4.03 9 9-4.03 9-9 9zm0-11c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"}]}

   {:name "AV Timer"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M22 34c0 1.1.9 2 2 2s2-.9 2-2-.9-2-2-2-2 .9-2 2zm0-28v8h4v-3.84c6.78.97 12 6.79 12 13.84 0 7.73-6.27 14-14 14s-14-6.27-14-14c0-3.36 1.18-6.43 3.15-8.85L24 26l2.83-2.83-13.6-13.6-.02.04C8.84 12.89 6 18.11 6 24c0 9.94 8.04 18 17.99 18S42 33.94 42 24 33.94 6 23.99 6H22zm14 18c0-1.1-.9-2-2-2s-2 .9-2 2 .9 2 2 2 2-.9 2-2zm-24 0c0 1.1.9 2 2 2s2-.9 2-2-.9-2-2-2-2 .9-2 2z"}]}

   {:name "Closed Caption"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 8H10c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4V12c0-2.21-1.79-4-4-4zM22 22h-3v-1h-4v6h4v-1h3v2c0 1.1-.89 2-2 2h-6c-1.11 0-2-.9-2-2v-8c0-1.1.89-2 2-2h6c1.11 0 2 .9 2 2v2zm14 0h-3v-1h-4v6h4v-1h3v2c0 1.1-.89 2-2 2h-6c-1.11 0-2-.9-2-2v-8c0-1.1.89-2 2-2h6c1.11 0 2 .9 2 2v2z"}]}

   {:name "Equalizer"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M20 40h8V8h-8v32zM8 40h8V24H8v16zm24-22v22h8V18h-8z"}]}

   {:name "Explicit"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 6H10c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4V10c0-2.21-1.79-4-4-4zm-8 12h-8v4h8v4h-8v4h8v4H18V14h12v4z"}]}

   {:name "Fast Forward"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M8 36l17-12L8 12v24zm18-24v24l17-12-17-12z"}]}

   {:name "Fast Rewind"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M22 36V12L5 24l17 12zm1-12l17 12V12L23 24z"}]}

   {:name "Games"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M30 15V4H18v11l6 6 6-6zm-15 3H4v12h11l6-6-6-6zm3 15v11h12V33l-6-6-6 6zm15-15l-6 6 6 6h11V18H33z"}]}

   {:name "Hearing"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 40c-.57 0-1.13-.12-1.53-.3-1.41-.75-2.43-1.77-3.42-4.77-1.03-3.11-2.94-4.58-4.79-6.01-1.58-1.22-3.22-2.48-4.63-5.05C18.58 21.95 18 19.86 18 18c0-5.61 4.39-10 10-10s10 4.39 10 10h4c0-7.85-6.15-14-14-14s-14 6.15-14 14c0 2.53.76 5.3 2.13 7.8 1.82 3.31 3.97 4.96 5.7 6.3 1.62 1.25 2.79 2.15 3.43 4.09 1.2 3.63 2.75 5.68 5.45 7.1 1.04.47 2.14.71 3.29.71 4.41 0 8-3.59 8-8h-4c0 2.21-1.79 4-4 4zM15.27 5.27l-2.83-2.83C8.46 6.42 6 11.92 6 18s2.46 11.58 6.44 15.56l2.83-2.83C12.01 27.47 10 22.97 10 18s2.01-9.47 5.27-12.73zM23 18c0 2.76 2.24 5 5 5s5-2.24 5-5-2.24-5-5-5-5 2.24-5 5z"}]}

   {:name "High Quality"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 8H10c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h28c2.21 0 4-1.79 4-4V12c0-2.21-1.79-4-4-4zM22 30h-3v-4h-4v4h-3V18h3v5h4v-5h3v12zm14-2c0 1.1-.89 2-2 2h-1.5v3h-3v-3H28c-1.11 0-2-.9-2-2v-8c0-1.1.89-2 2-2h6c1.11 0 2 .9 2 2v8zm-7-1h4v-6h-4v6z"}]}

   {:name "Loop"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 8V2l-8 8 8 8v-6c6.63 0 12 5.37 12 12 0 2.03-.51 3.93-1.39 5.61l2.92 2.92C39.08 30.05 40 27.14 40 24c0-8.84-7.16-16-16-16zm0 28c-6.63 0-12-5.37-12-12 0-2.03.51-3.93 1.39-5.61l-2.92-2.92C8.92 17.95 8 20.86 8 24c0 8.84 7.16 16 16 16v6l8-8-8-8v6z"}]}

   {:name "Mic"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 28c3.31 0 5.98-2.69 5.98-6L30 10c0-3.32-2.68-6-6-6-3.31 0-6 2.68-6 6v12c0 3.31 2.69 6 6 6zm10.6-6c0 6-5.07 10.2-10.6 10.2-5.52 0-10.6-4.2-10.6-10.2H10c0 6.83 5.44 12.47 12 13.44V42h4v-6.56c6.56-.97 12-6.61 12-13.44h-3.4z"}]}

   {:name "Mic None"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 28c3.31 0 5.98-2.69 5.98-6L30 10c0-3.32-2.68-6-6-6-3.31 0-6 2.68-6 6v12c0 3.31 2.69 6 6 6zM21.6 9.8c0-1.32 1.08-2.4 2.4-2.4 1.32 0 2.4 1.08 2.4 2.4l-.02 12.4c0 1.32-1.07 2.4-2.38 2.4-1.32 0-2.4-1.08-2.4-2.4V9.8zm13 12.2c0 6-5.07 10.2-10.6 10.2-5.52 0-10.6-4.2-10.6-10.2H10c0 6.83 5.44 12.47 12 13.44V42h4v-6.56c6.56-.97 12-6.61 12-13.44h-3.4z"}]}

   {:name "Mic Off"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 22h-3.4c0 1.49-.31 2.87-.87 4.1l2.46 2.46C37.33 26.61 38 24.38 38 22zm-8.03.33c0-.11.03-.22.03-.33V10c0-3.32-2.69-6-6-6s-6 2.68-6 6v.37l11.97 11.96zM8.55 6L6 8.55l12.02 12.02v1.44c0 3.31 2.67 6 5.98 6 .45 0 .88-.06 1.3-.15l3.32 3.32c-1.43.66-3 1.03-4.62 1.03-5.52 0-10.6-4.2-10.6-10.2H10c0 6.83 5.44 12.47 12 13.44V42h4v-6.56c1.81-.27 3.53-.9 5.08-1.81L39.45 42 42 39.46 8.55 6z"}]}

   {:name "Movie"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M36 8l4 8h-6l-4-8h-4l4 8h-6l-4-8h-4l4 8h-6l-4-8H8c-2.21 0-3.98 1.79-3.98 4L4 36c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4V8h-8z"}]}

   {:name "My Library Add"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M8 12H4v28c0 2.21 1.79 4 4 4h28v-4H8V12zm32-8H16c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h24c2.21 0 4-1.79 4-4V8c0-2.21-1.79-4-4-4zm-2 18h-8v8h-4v-8h-8v-4h8v-8h4v8h8v4z"}]}

   {:name "My Library Books"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M8 12H4v28c0 2.21 1.79 4 4 4h28v-4H8V12zm32-8H16c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h24c2.21 0 4-1.79 4-4V8c0-2.21-1.79-4-4-4zm-2 18H18v-4h20v4zm-8 8H18v-4h12v4zm8-16H18v-4h20v4z"}]}

   {:name "My Library Music"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 4H16c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h24c2.21 0 4-1.79 4-4V8c0-2.21-1.79-4-4-4zm-4 10h-6v11c0 2.76-2.24 5-5 5s-5-2.24-5-5 2.24-5 5-5c1.13 0 2.16.39 3 1.02V10h8v4zM8 12H4v28c0 2.21 1.79 4 4 4h28v-4H8V12z"}]}

   {:name "New Releases"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M46 24l-4.88-5.56.68-7.37-7.22-1.63-3.78-6.36L24 6l-6.8-2.92-3.78 6.36-7.22 1.63.68 7.37L2 24l4.88 5.56-.68 7.37 7.22 1.63 3.78 6.36L24 42l6.8 2.92 3.78-6.36 7.22-1.63-.68-7.37L46 24zM26 34h-4v-4h4v4zm0-8h-4V14h4v12z"}]}

   {:name "Not Interested"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4C12.95 4 4 12.95 4 24s8.95 20 20 20 20-8.95 20-20S35.05 4 24 4zm0 36c-8.84 0-16-7.16-16-16 0-3.7 1.27-7.09 3.37-9.8L33.8 36.63C31.09 38.73 27.7 40 24 40zm12.63-6.2L14.2 11.37C16.91 9.27 20.3 8 24 8c8.84 0 16 7.16 16 16 0 3.7-1.27 7.09-3.37 9.8z"}]}

   {:name "Pause"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M12 38h8V10h-8v28zm16-28v28h8V10h-8z"}]}

   {:name "Pause Circle Fill"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4C12.95 4 4 12.95 4 24s8.95 20 20 20 20-8.95 20-20S35.05 4 24 4zm-2 28h-4V16h4v16zm8 0h-4V16h4v16z"}]}

   {:name "Pause Circle Outline"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M18 32h4V16h-4v16zm6-28C12.95 4 4 12.95 4 24s8.95 20 20 20 20-8.95 20-20S35.05 4 24 4zm0 36c-8.82 0-16-7.18-16-16S15.18 8 24 8s16 7.18 16 16-7.18 16-16 16zm2-8h4V16h-4v16z"}]}

   {:name "Play"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:style {:stroke nil} :d "M16 10v28l22-14z"}]}

   {:name "Play Circle Fill"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4C12.95 4 4 12.95 4 24s8.95 20 20 20 20-8.95 20-20S35.05 4 24 4zm-4 29V15l12 9-12 9z"}]}

   {:name "Play Circle Outline"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M20 33l12-9-12-9v18zm4-29C12.95 4 4 12.95 4 24s8.95 20 20 20 20-8.95 20-20S35.05 4 24 4zm0 36c-8.82 0-16-7.18-16-16S15.18 8 24 8s16 7.18 16 16-7.18 16-16 16z"}]}

   {:name "Play Shopping Bag"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M32 12V8c0-2.21-1.79-4-4-4h-8c-2.21 0-4 1.79-4 4v4H4v26c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4V12H32zM20 8h8v4h-8V8zm-2 28V18l15 8-15 10z"}]}

   {:name "Playlist Add"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M28 20H4v4h24v-4zm0-8H4v4h24v-4zm8 16v-8h-4v8h-8v4h8v8h4v-8h8v-4h-8zM4 32h16v-4H4v4z"}]}

   {:name "Queue"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M8 12H4v28c0 2.21 1.79 4 4 4h28v-4H8V12zm32-8H16c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h24c2.21 0 4-1.79 4-4V8c0-2.21-1.79-4-4-4zm-2 18h-8v8h-4v-8h-8v-4h8v-8h4v8h8v4z"}]}

   {:name "Queue Music"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M30 12H6v4h24v-4zm0 8H6v4h24v-4zM6 32h16v-4H6v4zm28-20v16.37c-.63-.23-1.29-.37-2-.37-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6V16h6v-4H34z"}]}

   {:name "Radio"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6.47 12.3C5.02 12.87 4 14.33 4 16v24c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4V16c0-2.21-1.79-4-4-4H16.61l16.53-6.67L31.76 2 6.47 12.3zM14 40c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm26-16h-4v-4h-4v4H8v-8h32v8z"}]}

   {:name "Recent Actors"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M42 10v28h4V10h-4zm-8 28h4V10h-4v28zm-6-28H4c-1.1 0-2 .9-2 2v24c0 1.1.9 2 2 2h24c1.1 0 2-.9 2-2V12c0-1.1-.9-2-2-2zm-12 5.5c2.48 0 4.5 2.02 4.5 4.5 0 2.49-2.02 4.5-4.5 4.5s-4.5-2.01-4.5-4.5c0-2.48 2.02-4.5 4.5-4.5zM25 34H7v-1.5c0-3 6-4.5 9-4.5s9 1.5 9 4.5V34z"}]}

   {:name "Repeat"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 14h20v6l8-8-8-8v6H10v12h4v-8zm20 20H14v-6l-8 8 8 8v-6h24V26h-4v8z"}]}

   {:name "Repeat One"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M14 14h20v6l8-8-8-8v6H10v12h4v-8zm20 20H14v-6l-8 8 8 8v-6h24V26h-4v8zm-8-4V18h-2l-4 2v2h3v8h3z"}]}

   {:name "Replay"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 10V2L14 12l10 10v-8c6.63 0 12 5.37 12 12s-5.37 12-12 12-12-5.37-12-12H8c0 8.84 7.16 16 16 16s16-7.16 16-16-7.16-16-16-16z"}]}

   {:name "Shuffle"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M21.17 18.34L10.83 8 8 10.83l10.34 10.34 2.83-2.83zM29 8l4.09 4.09L8 37.17 10.83 40l25.09-25.09L40 19V8H29zm.66 18.83l-2.83 2.83 6.26 6.26L29 40h11V29l-4.09 4.09-6.25-6.26z"}]}

   {:name "Skip Next"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d "M12 36l17-12-17-12v24zm20-24v24h4V12h-4z"}]}

   {:name "Skip Previous"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil} :d "M12 12h4v24h-4zm7 12l17 12V12z"}]}

   {:name "Snooze"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M15.76 6.78l-2.57-3.06L4 11.43l2.57 3.06 9.19-7.71zM44 11.44l-9.19-7.71-2.57 3.06 9.19 7.71L44 11.44zM23.99 8C14.04 8 6 16.06 6 26s8.04 18 17.99 18S42 35.94 42 26 33.94 8 23.99 8zM24 40c-7.73 0-14-6.27-14-14s6.27-14 14-14 14 6.27 14 14-6.26 14-14 14zm-6-18h7.25L18 30.4V34h12v-4h-7.25L30 21.6V18H18v4z"}]}

   {:name "Stop"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:style {:stroke nil} :d "M12 12h24v24H12z"}]}

   {:name "Subtitles"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 8H8c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4V12c0-2.21-1.79-4-4-4zM8 24h8v4H8v-4zm20 12H8v-4h20v4zm12 0h-8v-4h8v4zm0-8H20v-4h20v4z"}]}

   {:name "Surround Sound"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 8H8c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4V12c0-2.21-1.79-4-4-4zM15.51 32.49l-2.83 2.83C9.57 32.19 8 28.1 8 24c0-4.1 1.57-8.19 4.69-11.31l2.83 2.83C13.18 17.85 12 20.93 12 24c0 3.07 1.17 6.15 3.51 8.49zM24 32c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm11.31 3.31l-2.83-2.83C34.83 30.15 36 27.07 36 24c0-3.07-1.18-6.15-3.51-8.49l2.83-2.83C38.43 15.81 40 19.9 40 24c0 4.1-1.57 8.19-4.69 11.31zM24 20c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z"}]}

   {:name "Video Collection"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M8 12H4v28c0 2.21 1.79 4 4 4h28v-4H8V12zm32-8H16c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h24c2.21 0 4-1.79 4-4V8c0-2.21-1.79-4-4-4zM24 29V11l12 9-12 9z"}]}

   {:name "Videocam"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 21v-7c0-1.1-.9-2-2-2H8c-1.1 0-2 .9-2 2v20c0 1.1.9 2 2 2h24c1.1 0 2-.9 2-2v-7l8 8V13l-8 8z"}]}

   {:name "Videocam Off"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M42 13l-8 8v-7c0-1.1-.9-2-2-2H19.64L42 34.36V13zM6.55 4L4 6.55 9.45 12H8c-1.1 0-2 .9-2 2v20c0 1.1.9 2 2 2h24c.41 0 .77-.15 1.09-.37L39.46 42 42 39.45 6.55 4z"}]}

   {:name "Volume Down"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M37 24c0-3.53-2.04-6.58-5-8.05v16.11c2.96-1.48 5-4.53 5-8.06zm-27-6v12h8l10 10V8L18 18h-8z"}]}

   {:name "Volume Mute"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path {:style {:stroke nil} :d "M14 18v12h8l10 10V8L22 18h-8z"}]}

   {:name "Volume Off"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M33 24c0-3.53-2.04-6.58-5-8.05v4.42l4.91 4.91c.06-.42.09-.85.09-1.28zm5 0c0 1.88-.41 3.65-1.08 5.28l3.03 3.03C41.25 29.82 42 27 42 24c0-8.56-5.99-15.72-14-17.54v4.13c5.78 1.72 10 7.07 10 13.41zM8.55 6L6 8.55 15.45 18H6v12h8l10 10V26.55l8.51 8.51c-1.34 1.03-2.85 1.86-4.51 2.36v4.13c2.75-.63 5.26-1.89 7.37-3.62L39.45 42 42 39.45l-18-18L8.55 6zM24 8l-4.18 4.18L24 16.36V8z"}]}

   {:name "Volume Up"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M6 18v12h8l10 10V8L14 18H6zm27 6c0-3.53-2.04-6.58-5-8.05v16.11c2.96-1.48 5-4.53 5-8.06zM28 6.46v4.13c5.78 1.72 10 7.07 10 13.41s-4.22 11.69-10 13.41v4.13c8.01-1.82 14-8.97 14-17.54S36.01 8.28 28 6.46z"}]}

   {:name "Web"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 8H8c-2.21 0-3.98 1.79-3.98 4L4 36c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4V12c0-2.21-1.79-4-4-4zM30 36H8v-8h22v8zm0-10H8v-8h22v8zm10 10h-8V18h8v18z"}]}


   ;; communication
   {:name "Business"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 14v-8h-20v36h40v-28h-20zm-12 24h-4v-4h4v4zm0-8h-4v-4h4v4zm0-8h-4v-4h4v4zm0-8h-4v-4h4v4zm8 24h-4v-4h4v4zm0-8h-4v-4h4v4zm0-8h-4v-4h4v4zm0-8h-4v-4h4v4zm20 24h-16v-4h4v-4h-4v-4h4v-4h-4v-4h16v20zm-4-16h-4v4h4v-4zm0 8h-4v4h4v-4z"}]}

   {:name "Call"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M13.25 21.59c2.88 5.66 7.51 10.29 13.18 13.17l4.4-4.41c.55-.55 1.34-.71 2.03-.49 2.24.74 4.65 1.14 7.14 1.14 1.11 0 2 .89 2 2v7c0 1.11-.89 2-2 2-18.78 0-34-15.22-34-34 0-1.11.9-2 2-2h7c1.11 0 2 .89 2 2 0 2.49.4 4.9 1.14 7.14.22.69.06 1.48-.49 2.03l-4.4 4.42z"}]}

   {:name "Call End"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 18c-3.21 0-6.3.5-9.2 1.44v6.21c0 .79-.46 1.47-1.12 1.8-1.95.98-3.74 2.23-5.33 3.7-.36.35-.85.57-1.4.57-.55 0-1.05-.22-1.41-.59l-4.95-4.95c-.37-.37-.59-.87-.59-1.42 0-.55.22-1.05.59-1.42 6.09-5.79 14.34-9.34 23.41-9.34s17.32 3.55 23.41 9.34c.37.36.59.87.59 1.42 0 .55-.22 1.05-.59 1.41l-4.95 4.95c-.36.36-.86.59-1.41.59-.54 0-1.04-.22-1.4-.57-1.59-1.47-3.38-2.72-5.33-3.7-.66-.33-1.12-1.01-1.12-1.8v-6.21c-2.9-.93-5.99-1.43-9.2-1.43z"}]}

   {:name "Call Made"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M18 10v4h13.17l-23.17 23.17 2.83 2.83 23.17-23.17v13.17h4v-20z"}]}

   {:name "Call Merge"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 40.83l2.83-2.83-6.83-6.83-2.83 2.83 6.83 6.83zm-19-24.83h7v11.17l-10.83 10.83 2.83 2.83 12-12v-12.83h7l-9-9-9 9z"}]}

   {:name "Call Missed"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M39.17 14l-15.17 15.17-11.17-11.17h9.17v-4h-16v16h4v-9.17l14 14 18-18z"}]}

   {:name "Call Received"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 10.83l-2.83-2.83-23.17 23.17v-13.17h-4v20h20v-4h-13.17z"}]}

   {:name "Call Split"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M28 8l4.59 4.59-5.76 5.75 2.83 2.83 5.75-5.76 4.59 4.59v-12zm-8 0h-12v12l4.59-4.59 9.41 9.42v15.17h4v-16.83l-10.59-10.58z"}]}

   {:name "Chat"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 4h-32c-2.21 0-3.98 1.79-3.98 4l-.02 36 8-8h28c2.21 0 4-1.79 4-4v-24c0-2.21-1.79-4-4-4zm-28 14h24v4h-24v-4zm16 10h-16v-4h16v4zm8-12h-24v-4h24v4z"}]}

   {:name "Contacts"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 0h-32v4h32v-4zm-32 48h32v-4h-32v4zm32-40h-32c-2.21 0-4 1.79-4 4v24c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4v-24c0-2.21-1.79-4-4-4zm-16 5.5c2.48 0 4.5 2.02 4.5 4.5 0 2.49-2.02 4.5-4.5 4.5s-4.5-2.01-4.5-4.5c0-2.48 2.02-4.5 4.5-4.5zm10 20.5h-20v-3c0-3.33 6.67-5 10-5s10 1.67 10 5v3z"}]}

   {:name "Dialer Sip"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 6h-2v10h2v-10zm-4 4h-4v-2h4v-2h-6v6h4v2h-4v2h6v-6zm6-4v10h2v-4h4v-6h-6zm4 4h-2v-2h2v2zm0 21c-2.49 0-4.89-.4-7.14-1.14-.69-.22-1.48-.06-2.03.49l-4.4 4.41c-5.66-2.88-10.29-7.51-13.18-13.17l4.4-4.41c.55-.55.71-1.34.49-2.03-.74-2.25-1.14-4.66-1.14-7.15 0-1.11-.89-2-2-2h-7c-1.11 0-2 .89-2 2 0 18.78 15.22 34 34 34 1.11 0 2-.89 2-2v-7c0-1.11-.89-2-2-2z"}]}

   {:name "Dialpad"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 38c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm-12-36c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0 12c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0 12c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm24-16c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm-12 16c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm12 0c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0-12c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm-12 0c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0-12c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z"}]}

   {:name "Dnd On"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4c-11.05 0-20 8.95-20 20s8.95 20 20 20 20-8.95 20-20-8.95-20-20-20zm0 36c-8.84 0-16-7.16-16-16 0-3.7 1.27-7.09 3.37-9.8l22.43 22.43c-2.71 2.1-6.1 3.37-9.8 3.37zm12.63-6.2l-22.43-22.43c2.71-2.1 6.1-3.37 9.8-3.37 8.84 0 16 7.16 16 16 0 3.7-1.27 7.09-3.37 9.8z"}]}

   {:name "Email"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 8h-32c-2.21 0-3.98 1.79-3.98 4l-.02 24c0 2.21 1.79 4 4 4h32c2.21 0 4-1.79 4-4v-24c0-2.21-1.79-4-4-4zm0 8l-16 10-16-10v-4l16 10 16-10v4z"}]}

   {:name "Forum"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M42 12h-4v18h-26v4c0 1.1.9 2 2 2h22l8 8v-30c0-1.1-.9-2-2-2zm-8 12v-18c0-1.1-.9-2-2-2h-26c-1.1 0-2 .9-2 2v28l8-8h20c1.1 0 2-.9 2-2z"}]}

   {:name "Import Export"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M18 6l-8 7.98h6v14.02h4v-14.02h6l-8-7.98zm14 28.02v-14.02h-4v14.02h-6l8 7.98 8-7.98h-6z"}]}

   {:name "Invert Colors Off"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M41.29 41.75l-4.71-4.71-12.58-12.58-7.13-7.13-2.83-2.83-5.5-5.5-2.54 2.55 5.56 5.56c-5.1 6.28-4.72 15.53 1.12 21.38 3.12 3.12 7.22 4.69 11.31 4.69 3.57 0 7.14-1.19 10.06-3.56l5.4 5.38 2.55-2.55-.71-.7zm-17.29-2.58c-3.21 0-6.22-1.25-8.48-3.51-2.27-2.27-3.52-5.28-3.52-8.49 0-2.64.86-5.14 2.42-7.21l9.58 9.59v9.62zm0-28.97v9.16l14.51 14.51c2.73-5.91 1.68-13.14-3.2-18.02l-11.31-11.31-7.41 7.41 2.83 2.83 4.58-4.58z"}]}

   {:name "Invert Colors On"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M35.31 15.86l-11.31-11.32-11.31 11.32c-6.25 6.25-6.25 16.38 0 22.63 3.12 3.12 7.22 4.69 11.31 4.69s8.19-1.56 11.31-4.69c6.25-6.25 6.25-16.38 0-22.63zm-11.31 23.31c-3.21 0-6.22-1.25-8.48-3.52-2.27-2.26-3.52-5.27-3.52-8.48s1.25-6.22 3.52-8.49l8.48-8.48v28.97z"}]}

   {:name "Live Help"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M38 4h-28c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h8l6 6 6-6h8c2.21 0 4-1.79 4-4v-28c0-2.21-1.79-4-4-4zm-12 32h-4v-4h4v4zm4.13-15.49l-1.79 1.84c-1.44 1.44-2.34 2.65-2.34 5.65h-4v-1c0-2.21.9-4.21 2.34-5.66l2.49-2.52c.72-.72 1.17-1.72 1.17-2.82 0-2.21-1.79-4-4-4s-4 1.79-4 4h-4c0-4.42 3.58-8 8-8s8 3.58 8 8c0 1.76-.71 3.35-1.87 4.51z"}]}

   {:name "Location Off"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 13c2.76 0 5 2.24 5 5 0 1.47-.65 2.78-1.66 3.7l7.26 7.26c1.95-3.72 3.4-7.59 3.4-10.96 0-7.73-6.27-14-14-14-3.96 0-7.53 1.65-10.07 4.29l6.37 6.37c.91-1.01 2.23-1.66 3.7-1.66zm8.75 19.2l-9.25-9.25-.22-.22-16.74-16.73-2.54 2.55 6.36 6.36c-.23.99-.36 2.02-.36 3.09 0 10.5 14 26 14 26s3.34-3.7 6.75-8.7l6.7 6.7 2.55-2.55-7.25-7.25z"}]}

   {:name "Location On"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M24 4c-7.73 0-14 6.27-14 14 0 10.5 14 26 14 26s14-15.5 14-26c0-7.73-6.27-14-14-14zm0 19c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z"}]}

   {:name "Message"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 4h-32c-2.21 0-3.98 1.79-3.98 4l-.02 36 8-8h28c2.21 0 4-1.79 4-4v-24c0-2.21-1.79-4-4-4zm-4 24h-24v-4h24v4zm0-6h-24v-4h24v4zm0-6h-24v-4h24v4z"}]}

   {:name "Messenger"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 4h-32c-2.21 0-4 1.79-4 4v36l8-8h28c2.21 0 4-1.79 4-4v-24c0-2.21-1.79-4-4-4z"}]}

   {:name "No Sim"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M37.98 10c0-2.21-1.77-4-3.98-4h-14l-4.68 4.68 22.68 22.68-.02-23.36zm-30.68-2.24l-2.54 2.54 5.24 5.25v22.45c0 2.21 1.79 4 4 4h20.02c.7 0 1.35-.2 1.92-.51l3.76 3.76 2.54-2.55-34.94-34.94z"}]}

   {:name "Phone"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M13.25 21.59c2.88 5.66 7.51 10.29 13.18 13.17l4.4-4.41c.55-.55 1.34-.71 2.03-.49 2.24.74 4.65 1.14 7.14 1.14 1.11 0 2 .89 2 2v7c0 1.11-.89 2-2 2-18.78 0-34-15.22-34-34 0-1.11.9-2 2-2h7c1.11 0 2 .89 2 2 0 2.49.4 4.9 1.14 7.14.22.69.06 1.48-.49 2.03l-4.4 4.42z"}]}

   {:name "Portable Wifi off"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M35.12 28.48c.56-1.38.88-2.89.88-4.48 0-6.63-5.37-12-12-12-1.59 0-3.1.32-4.49.88l3.25 3.25c.41-.07.82-.13 1.24-.13 4.42 0 8 3.58 8 8 0 .43-.04.85-.11 1.25l3.23 3.23zm-11.12-20.48c8.84 0 16 7.16 16 16 0 2.71-.7 5.24-1.89 7.47l2.94 2.94c1.86-3.03 2.95-6.59 2.95-10.41 0-11.05-8.96-20-20-20-3.82 0-7.38 1.09-10.41 2.95l2.92 2.92c2.23-1.19 4.78-1.87 7.49-1.87zm-17.46-3l-2.54 2.55 4.21 4.21c-2.63 3.38-4.21 7.62-4.21 12.24 0 7.39 4.02 13.83 9.99 17.29l2-3.46c-4.77-2.76-7.99-7.92-7.99-13.83 0-3.51 1.14-6.75 3.06-9.39l2.87 2.87c-1.22 1.88-1.93 4.11-1.93 6.52 0 4.44 2.41 8.3 5.99 10.38l2.02-3.48c-2.39-1.39-4.01-3.94-4.01-6.9 0-1.29.34-2.49.88-3.57l3.16 3.16-.04.41c0 2.21 1.79 4 4 4l.41-.04.02.02 15.02 15.02 2.55-2.55-33.46-33.45-2-2z"}]}

   {:name "Quick Contacts Dialer"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M44 6h-40c-2.21 0-4 1.79-4 4v28c0 2.21 1.79 4 4 4h40c2.21 0 3.98-1.79 3.98-4l.02-28c0-2.21-1.79-4-4-4zm-28 6c3.31 0 6 2.69 6 6 0 3.32-2.69 6-6 6s-6-2.68-6-6c0-3.31 2.69-6 6-6zm12 24h-24v-2c0-4 8-6.2 12-6.2s12 2.2 12 6.2v2zm7.7-8h3.28l3.02 4-3.99 3.99c-2.61-1.96-4.56-4.75-5.46-7.99-.35-1.28-.55-2.61-.55-4s.2-2.72.56-4c.89-3.24 2.84-6.03 5.46-7.99l3.98 3.99-3.02 4h-3.28c-.44 1.25-.7 2.6-.7 4s.25 2.75.7 4z"}]}

   {:name "Ring Volume"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M47.41 33.34c-6.09-5.79-14.34-9.34-23.41-9.34s-17.32 3.55-23.41 9.34c-.37.36-.59.87-.59 1.42 0 .55.22 1.05.59 1.41l4.95 4.95c.36.36.86.59 1.41.59.54 0 1.04-.22 1.4-.57 1.59-1.47 3.38-2.72 5.33-3.7.66-.33 1.12-1.01 1.12-1.8v-6.21c2.9-.93 5.99-1.43 9.2-1.43s6.3.5 9.2 1.44v6.21c0 .79.46 1.47 1.12 1.8 1.95.98 3.75 2.23 5.33 3.7.36.35.85.57 1.4.57.55 0 1.05-.22 1.42-.59l4.95-4.95c.36-.36.59-.86.59-1.41-.01-.56-.23-1.07-.6-1.43zm-5.09-20.83l-2.83-2.83-7.12 7.12 2.83 2.83s6.9-7.04 7.12-7.12zm-16.32-8.51h-4v10h4v-10zm-13.2 15.63l2.83-2.83-7.12-7.12-2.83 2.83c.22.08 7.12 7.12 7.12 7.12z"}]}

   {:name "Stay Current Landscape"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M2.02 14l-.02 20c0 2.21 1.79 4 4 4h36c2.21 0 4-1.79 4-4v-20c0-2.21-1.79-4-4-4h-36c-2.21 0-3.98 1.79-3.98 4zm35.98 0v20h-28v-20h28z"}]}

   {:name "Stay Current Portrait"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 2.02l-20-.02c-2.21 0-3.98 1.79-3.98 4v36c0 2.21 1.77 4 3.98 4h20c2.21 0 4-1.79 4-4v-36c0-2.21-1.79-3.98-4-3.98zm0 35.98h-20v-28h20v28z"}]}

   {:name "Stay Primary Landscape"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 2.02l-20-.02c-2.21 0-3.98 1.79-3.98 4v36c0 2.21 1.77 4 3.98 4h20c2.21 0 4-1.79 4-4v-36c0-2.21-1.79-3.98-4-3.98zm0 35.98h-20v-28h20v28z"}]}

   {:name "Stay Primary Portrait"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M34 2.02l-20-.02c-2.21 0-3.98 1.79-3.98 4v36c0 2.21 1.77 4 3.98 4h20c2.21 0 4-1.79 4-4v-36c0-2.21-1.79-3.98-4-3.98zm0 35.98h-20v-28h20v28z"}]}

   {:name "Swap Calls"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M36 8l-8 8h6v14c0 2.21-1.79 4-4 4s-4-1.79-4-4v-14c0-4.41-3.59-8-8-8s-8 3.59-8 8v14h-6l8 8 8-8h-6v-14c0-2.21 1.79-4 4-4s4 1.79 4 4v14c0 4.41 3.59 8 8 8s8-3.59 8-8v-14h6l-8-8z"}]}

   {:name "Textsms"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M40 4h-32c-2.21 0-3.98 1.79-3.98 4l-.02 36 8-8h28c2.21 0 4-1.79 4-4v-24c0-2.21-1.79-4-4-4zm-22 18h-4v-4h4v4zm8 0h-4v-4h4v4zm8 0h-4v-4h4v4z"}]}

   {:name "Voicemail"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M37 12c-6.08 0-11 4.92-11 11 0 2.66.94 5.1 2.51 7h-9.03c1.57-1.9 2.51-4.34 2.51-7 0-6.08-4.92-11-11-11s-10.99 4.92-10.99 11 4.92 11 11 11h26c6.08 0 11-4.92 11-11s-4.92-11-11-11zm-26 18c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7zm26 0c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"}]}

   {:name "VPN Key"
    :id #uuid (uuid/random)
    :type :builtin
    :view-box [0 0 48 48]
    :data [:path
           {:style {:stroke nil}
            :d
            "M25.3 20c-1.65-4.66-6.08-8-11.3-8-6.63 0-12 5.37-12 12s5.37 12 12 12c5.22 0 9.65-3.34 11.3-8h8.7v8h8v-8h4v-8h-20.7zm-11.3 8c-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4-1.79 4-4 4z"}]}
   ])

