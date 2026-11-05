;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.text.layout
  "Language of text shapes, as the CJK text options read it. A text has one
  language: every paragraph carries the same `:text-language` tag. A text
  without one takes the file language."
  (:require
   [app.common.types.text :as txt]
   [app.util.i18n :refer [tr]]))

(defn- get-paragraph-languages
  "Language of each paragraph of `content`, `default-language` for the ones
  without; a content without paragraphs reads as one such paragraph."
  [content default-language]
  (let [paragraphs (txt/node-seq txt/is-paragraph-node? content)]
    (if (seq paragraphs)
      (map #(or (:text-language %) default-language) paragraphs)
      [default-language])))

(defn get-texts-language
  "Language of the text `contents`: the tag all their paragraphs share, nil
  when none has a language, `:multiple` when they differ. A paragraph
  without language takes `default-language`, so with none it differs from
  one with a language."
  ([contents]
   (get-texts-language contents nil))
  ([contents default-language]
   (let [languages (into #{} (mapcat #(get-paragraph-languages % default-language)) contents)]
     (if (> (count languages) 1)
       :multiple
       (first languages)))))

(defn language-label
  "Name of the language `tag` in the Language panel row: \"Mixed\" for
  `:multiple`, the tag itself when it is not one of the listed languages."
  [tag]
  (case tag
    :multiple    (tr "settings.multiple")
    "ja"         (tr "workspace.options.text-options.profile-japanese")
    "ko"         (tr "workspace.options.text-options.profile-korean")
    "zh-Hans-CN" (tr "workspace.options.text-options.language-chinese-variant"
                     (tr "workspace.options.text-options.profile-chinese-mainland-simplified"))
    "zh-Hant-CN" (tr "workspace.options.text-options.language-chinese-variant"
                     (tr "workspace.options.text-options.profile-chinese-mainland-traditional"))
    "zh-Hant-TW" (tr "workspace.options.text-options.language-chinese-variant"
                     (tr "workspace.options.text-options.profile-chinese-taiwan"))
    "zh-Hant-HK" (tr "workspace.options.text-options.language-chinese-variant"
                     (tr "workspace.options.text-options.profile-chinese-hong-kong"))
    tag))

(defn language-options
  "Rows of the language dropdown: one group per language, the `selected`
  tag checked."
  [selected]
  (let [option (fn [tag label]
                 {:id tag :label label :selected (= selected tag)})]
    [{:id "japanese" :header true
      :label (tr "workspace.options.text-options.language-group-japanese")}
     (option "ja" (tr "workspace.options.text-options.profile-japanese"))
     {:id "korean" :header true
      :label (tr "workspace.options.text-options.language-group-korean")}
     (option "ko" (tr "workspace.options.text-options.profile-korean"))
     {:id "chinese" :header true
      :label (tr "workspace.options.text-options.language-group-chinese")}
     (option "zh-Hans-CN" (tr "workspace.options.text-options.profile-chinese-mainland-simplified"))
     (option "zh-Hant-CN" (tr "workspace.options.text-options.profile-chinese-mainland-traditional"))
     (option "zh-Hant-TW" (tr "workspace.options.text-options.profile-chinese-taiwan"))
     (option "zh-Hant-HK" (tr "workspace.options.text-options.profile-chinese-hong-kong"))]))
