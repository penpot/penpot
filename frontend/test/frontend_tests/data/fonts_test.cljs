;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.fonts-test
  "Tests for the upload metadata resolution in app.main.data.fonts."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.fonts :as df]
   [app.main.data.uploads :as uploads]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]))

(t/deftest resolve-font-metadata-prefers-opentype-values
  (t/testing "opentype.js values win over the filename"
    (t/is (= {:font-family "Inter"
              :font-weight 700
              :font-style "normal"
              :variant-name "Bold"}
             (df/resolve-font-metadata {:family "Inter" :variant "Bold"}
                                       "Inter.ttf")))))

(t/deftest resolve-font-metadata-falls-back-to-filename
  (t/testing "derives weight and style from the filename when the variant is missing"
    (t/is (= {:font-family "Pretendard"
              :font-weight 600
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {:family "Pretendard" :variant nil}
                                       "Pretendard-SemiBold.otf"))))

  (t/testing "derives weight and style from the filename when the variant is blank"
    (t/is (= {:font-family "Lato"
              :font-weight 300
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {:family "Lato" :variant ""}
                                       "Lato-Light.ttf"))))

  (t/testing "derives the family from the filename when it is missing"
    (t/is (= {:font-family "Blender Inter"
              :font-weight 700
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {:family nil :variant nil}
                                       "Blender-Inter-Bold.ttf"))))

  (t/testing "derives family, weight and style from the filename when nothing is available"
    (t/is (= {:font-family "Pretendard"
              :font-weight 600
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {} "Pretendard-SemiBold.woff2"))))

  (t/testing "derives italic from the filename"
    (t/is (= {:font-family "Roboto"
              :font-weight 400
              :font-style "italic"
              :variant-name nil}
             (df/resolve-font-metadata {} "Roboto-Italic.ttf"))))

  (t/testing "separates a style token glued to a weight token"
    (t/is (= {:font-family "Roboto"
              :font-weight 700
              :font-style "italic"
              :variant-name nil}
             (df/resolve-font-metadata {:family "Roboto" :variant nil}
                                       "Roboto-BoldItalic.ttf"))))

  (t/testing "treats an oblique variant as italic"
    (t/is (= {:font-family "Roboto"
              :font-weight 700
              :font-style "italic"
              :variant-name "BoldOblique"}
             (df/resolve-font-metadata {:family "Roboto" :variant "BoldOblique"}
                                       "Roboto.ttf"))))

  (t/testing "handles filenames without extension"
    (t/is (= {:font-family "Lato"
              :font-weight 300
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {} "Lato-Light")))))

(t/deftest ^:async upload-font-variant-sends-variant-name
  (await
   (mock/with-mocks*
     {rp/cmd! mock/rpc-cmd-mock
      uploads/upload-blob-chunked
      (fn [_blob & _opts] (rx/of {:session-id (uuid/next)}))}
     (let [item {:data         {"font/ttf" (js/Uint8Array. 4)}
                 :team-id      (uuid/next)
                 :font-id      (uuid/next)
                 :font-family  "Roboto"
                 :font-weight  700
                 :font-style   "italic"
                 :variant-name "BoldOblique"}]
       (await (async/->promise (df/upload-font-variant item)))
       (let [{:keys [cmd params]} (first @mock/rpc-calls)]
         (t/is (= :create-font-variant cmd))
         (t/is (= "BoldOblique" (:variant-name params)))))
     (await (async/settle)))))

(defn- fake-font
  "A minimal opentype.js-like font object exposing only what
  prepare-font-entry reads."
  [names]
  #js {:getEnglishName (fn [k] (get names k))
       :tables #js {:hhea #js {:ascender 800 :descender -200}
                    :os2 #js {:usWinAscent 800
                              :usWinDescent 200
                              :sTypoAscender 800
                              :sTypoDescender -200
                              :fsSelection 0}}})

(t/deftest prepare-font-entry-falls-back-when-metadata-is-missing
  (t/testing "parsed font with a missing subfamily"
    (let [result (df/prepare-font-entry
                  {:font (fake-font {"preferredFamily" "Pretendard"})
                   :type "font/ttf"
                   :name "Pretendard-SemiBold.otf"
                   :data (js/Uint8Array. 4)})]
      (t/is (= "Pretendard" (:font-family result)))
      (t/is (= 600 (:font-weight result)))
      (t/is (= "normal" (:font-style result)))
      (t/is (nil? (:variant-name result)))
      (t/is (false? (:height-warning? result)))
      (t/is (= {:name "Pretendard-SemiBold.otf" :type "font/ttf"}
               (select-keys (:content result) [:name :type])))))

  (t/testing "unparseable woff2 entry is resolved from the filename"
    (let [result (df/prepare-font-entry
                  {:font nil
                   :type "font/woff2"
                   :name "Pretendard-SemiBold.woff2"
                   :data (js/Uint8Array. 4)})]
      (t/is (= "Pretendard" (:font-family result)))
      (t/is (= 600 (:font-weight result)))
      (t/is (= "normal" (:font-style result)))
      (t/is (false? (:height-warning? result)))))

  (t/testing "a blank first candidate does not shadow a valid second one"
    (let [result (df/prepare-font-entry
                  {:font (fake-font {"preferredFamily" "   "
                                     "preferredSubfamily" "  "
                                     "fontSubfamily" "Bold"})
                   :type "font/ttf"
                   :name "Roboto-Light.ttf"
                   :data (js/Uint8Array. 4)})]
      (t/is (= "Roboto" (:font-family result)))
      (t/is (= 700 (:font-weight result)))
      (t/is (= "normal" (:font-style result)))
      (t/is (= "Bold" (:variant-name result))))))
