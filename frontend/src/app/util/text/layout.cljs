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

(def ^:private language-groups
  "Languages a text can take, grouped as the language dropdown lists them:
  `[tag label-key]` pairs per group."
  [{:id "japanese"
    :label "workspace.options.text-options.language-group-japanese"
    :languages [["ja" "workspace.options.text-options.language-japanese"]]}
   {:id "korean"
    :label "workspace.options.text-options.language-group-korean"
    :languages [["ko" "workspace.options.text-options.language-korean"]]}
   {:id "chinese"
    :label "workspace.options.text-options.language-group-chinese"
    ;; Variant names, shown as "Chinese · <variant>" outside the group.
    :variants true
    :languages [["zh-Hans-CN" "workspace.options.text-options.language-chinese-mainland-simplified"]
                ["zh-Hant-CN" "workspace.options.text-options.language-chinese-mainland-traditional"]
                ["zh-Hant-TW" "workspace.options.text-options.language-chinese-taiwan"]
                ["zh-Hant-HK" "workspace.options.text-options.language-chinese-hong-kong"]]}])

(def ^:private language-entries
  (into {}
        (for [{:keys [variants languages]} language-groups
              [tag label] languages]
          [tag {:label label :variant variants}])))

(defn language-label
  "Name of the language `tag` in the Language panel row: \"Mixed\" for
  `:multiple`, the tag itself when it is not one of the listed languages."
  [tag]
  (if (= :multiple tag)
    (tr "settings.multiple")
    (if-let [{:keys [label variant]} (get language-entries tag)]
      (if variant
        (tr "workspace.options.text-options.language-chinese-variant" (tr label))
        (tr label))
      tag)))

(defn language-options
  "Rows of the language dropdown: one group per language, the `selected`
  tag checked."
  [selected]
  (into []
        (mapcat (fn [{:keys [id label languages]}]
                  (into [{:id id :header true :label (tr label)}]
                        (map (fn [[tag label]]
                               {:id tag :label (tr label) :selected (= selected tag)}))
                        languages)))
        language-groups))
