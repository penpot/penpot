;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text-editor
  "Draft related abstraction functions."
  (:require
   ["@penpot/draft-js" :as impl]
   [app.common.text :as txt]))

;; --- CONVERSION

(defn immutable-map->map
  [obj]
  (let [data (into {} (map (fn [[k v]] [(keyword k) v])) (seq obj))]
    (assoc data :fills (js->clj (:fills data) :keywordize-keys true))))

;; --- DRAFT-JS HELPERS

(defn create-editor-state
  ([]
   (impl/createEditorState nil nil))
  ([content]
   (impl/createEditorState content nil))
  ([content decorator]
   (impl/createEditorState content decorator)))

(defn create-decorator
  [type component]
  (impl/createDecorator type component))

(defn import-content
  [content]
  (-> content txt/convert-to-draft clj->js impl/convertFromRaw))

(defn export-content
  [content]
  (-> content
      (impl/convertToRaw)
      (js->clj :keywordize-keys true)
      (txt/convert-from-draft)))

(defn get-editor-current-plain-text
  [state]
  (.getPlainText (.getCurrentContent ^js state)))

(defn get-editor-current-content
  [state]
  (.getCurrentContent ^js state))

(defn ^boolean content-has-text?
  [content]
  (.hasText ^js content))

(defn editor-select-all
  [state]
  (impl/selectAll state))

(defn get-editor-block-data
  [block]
  (-> (.getData ^js block)
      (immutable-map->map)))

(defn get-editor-block-type
  [block]
  (.getType ^js block))

(defn get-editor-current-block-data
  [state]
  (let [block (impl/getCurrentBlock state)]
    (get-editor-block-data block)))

(defn is-current-empty
  [state]
  (impl/isCurrentEmpty state))

(defn get-editor-current-inline-styles
  [state]
  (if (impl/isCurrentEmpty state)
    (get-editor-current-block-data state)
    (-> (.getCurrentInlineStyle ^js state)
        (txt/styles-to-attrs)
        (dissoc :text-align :text-direction))))

(defn update-editor-current-block-data
  [state attrs]
  (impl/updateCurrentBlockData state (clj->js attrs)))

(defn update-editor-current-inline-styles
  [state attrs]
  (let [update-blocks
        (fn [state block-key]
          (if (empty? (impl/getBlockContent state block-key))
            (impl/updateBlockData state block-key (clj->js attrs))

            (let [attrs (-> (impl/getInlineStyle state block-key 0)
                            (txt/styles-to-attrs)
                            (dissoc :text-align :text-direction))]
              (impl/updateBlockData state block-key (clj->js attrs)))))

        state (impl/applyInlineStyle state (txt/attrs-to-styles attrs))
        selected (impl/getSelectedBlocks state)]
    (reduce update-blocks state selected)))

(defn update-editor-current-inline-styles-fn
  [state update-fn]
  (let [attrs (-> (.getCurrentInlineStyle ^js state)
                  (txt/styles-to-attrs)
                  (update-fn))]
    (impl/applyInlineStyle state (txt/attrs-to-styles attrs))))

(defn editor-split-block
  [state]
  (impl/splitBlockPreservingData state))

(defn add-editor-blur-selection
  [state]
  (impl/addBlurSelectionEntity state))

(defn remove-editor-blur-selection
  [state]
  (impl/removeBlurSelectionEntity state))

(defn cursor-to-end
  [state]
  (impl/cursorToEnd state))

(defn setup-block-styles
  [state blocks attrs]
  (if (empty? blocks)
    state
    (->> blocks
         (reduce
          (fn [state block-key]
            (impl/updateBlockData state block-key (clj->js attrs)))
          state))))

(defn apply-block-styles-to-content
  [state blocks]
  (if (empty? blocks)
    state
    (let [selection (impl/getSelection state)
          redfn
          (fn [state bkey]
            (let [attrs (-> (impl/getBlockData state bkey)
                            (js->clj :keywordize-keys true))]
              (-> state
                  (impl/selectBlock bkey)
                  (impl/applyInlineStyle (txt/attrs-to-styles attrs)))))]
      (as-> state $
        (reduce redfn $ blocks)
        (impl/setSelection $ selection)))))

(defn insert-text [state text attrs]
  (let [style (txt/attrs-to-styles attrs)]
    (impl/insertText state text (clj->js attrs) (clj->js style))))

(defn get-style-override [state]
  (.getInlineStyleOverride ^js state))

(defn set-style-override [state inline-style]
  (impl/setInlineStyleOverride state inline-style))

(defn content-equals [state other]
  (.equals (.getCurrentContent ^js state) (.getCurrentContent ^js other)))

(defn selection-equals [state other]
  (impl/selectionEquals (.getSelection state) (.getSelection other)))

(defn get-content-changes
  [old-state state]
  (let [old-blocks (js->clj (.toJS (.getBlockMap (.getCurrentContent ^js old-state)))
                            :keywordize-keys false)
        new-blocks (js->clj (.toJS (.getBlockMap (.getCurrentContent ^js state)))
                            :keywordize-keys false)]
    (merge
     (into {}
           (comp (filter #(contains? new-blocks (first %)))
                 (map (fn [[bkey bstate]]
                        [bkey
                         {:old (get bstate "text")
                          :new (get-in new-blocks [bkey "text"])}])))
           old-blocks)
     (into {}
           (comp (filter #(not (contains? old-blocks (first %))))
                 (map (fn [[bkey bstate]]
                        [bkey
                         {:old nil
                          :new (get bstate "text")}])))
           new-blocks))))
