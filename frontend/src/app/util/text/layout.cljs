;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.text.layout
  "Language of text shapes, as the CJK text options read it. A text has one
  language: every paragraph carries the same `:text-language` tag."
  (:require
   [app.common.types.text :as txt]
   [app.util.i18n :refer [tr]]))

(defn- get-paragraph-languages
  "Language of each paragraph of `content`, nil for the ones without; a
  content without paragraphs reads as one such paragraph."
  [content]
  (let [paragraphs (txt/node-seq txt/is-paragraph-node? content)]
    (if (seq paragraphs)
      (map :text-language paragraphs)
      [nil])))

(defn get-texts-language
  "Language of the text `contents`: the tag all their paragraphs share, nil
  when none has a language, `:multiple` when they differ. A paragraph
  without language differs from one with a language."
  [contents]
  (let [languages (into #{} (mapcat get-paragraph-languages) contents)]
    (if (> (count languages) 1)
      :multiple
      (first languages))))

(def ^:private language-groups
  "Languages a text can take, in the order the profile lists show them:
  each group lists its language tags."
  [{:id "japanese" :languages ["ja"]}
   {:id "chinese" :languages ["zh-Hans-CN" "zh-Hant-CN" "zh-Hant-HK" "zh-Hant-TW"]}
   {:id "korean" :languages ["ko"]}])

(defn- get-group-label
  [id]
  (case id
    "japanese" (tr "workspace.options.text-options.language-group-japanese")
    "chinese"  (tr "workspace.options.text-options.language-group-chinese")
    "korean"   (tr "workspace.options.text-options.language-group-korean")))

(defn language-label
  "Name of the language `tag`: \"Mixed\" for `:multiple`, the tag itself
  when it is not one of the listed languages."
  [tag]
  (case tag
    :multiple    (tr "settings.multiple")
    "ja"         (tr "workspace.options.text-options.language-japanese")
    "zh-Hans-CN" (tr "workspace.options.text-options.language-chinese-mainland-simplified")
    "zh-Hant-CN" (tr "workspace.options.text-options.language-chinese-mainland-traditional")
    "zh-Hant-HK" (tr "workspace.options.text-options.language-chinese-hong-kong")
    "zh-Hant-TW" (tr "workspace.options.text-options.language-chinese-taiwan")
    "ko"         (tr "workspace.options.text-options.language-korean")
    tag))

(defn language-options
  "Rows of the profile lists: one group per language, a header row
  `{:id :label :header true}` and then its languages, the `selected` tag
  checked."
  [selected]
  (into []
        (mapcat (fn [{:keys [id languages]}]
                  (into [{:id id :header true :label (get-group-label id)}]
                        (map (fn [tag]
                               {:id tag :label (language-label tag) :selected (= selected tag)}))
                        languages)))
        language-groups))
