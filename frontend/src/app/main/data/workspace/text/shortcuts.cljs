;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.text.shortcuts
  (:require
   [app.common.data :as d]
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts format https://github.com/ccampbell/mousetrap

(defn- generate-variant-props
  [text-values variant-id]
  (let [first-intersection        (fn [list1 list2] (first (filter (set list1) list2)))
        current-variant           (:font-variant-id text-values)
        bold-options              (cond
                                    (str/includes? current-variant "black")
                                    ["black" "bold" "700"]
                                    (str/includes? current-variant "700")
                                    ["700" "black" "bold"]
                                    :else
                                    ["bold" "black" "700"])
        current-variant-no-italic (cond
                                    (str/includes? current-variant "italic")
                                    (subs current-variant 0 (- (count current-variant) 6))
                                    (str/includes? current-variant "cursive")
                                    (subs current-variant 0 (- (count current-variant) 7))
                                    :else nil)
        regular-options           [current-variant-no-italic "regular" "normal" "400"]
        italic-options            [(when (and (not (str/includes? current-variant "bold"))
                                              (not (str/includes? current-variant "black"))
                                              (not (str/includes? current-variant "700")))
                                     (str current-variant "italic"))
                                   "italic" "cursive"]
        bold-italic-options       (cond
                                    (str/includes? current-variant "black")
                                    ["blackitalic" "blackcursive" "bolditalic" "700italic" "boldcursive" "700cursive"]
                                    (str/includes? current-variant "700")
                                    ["700italic" "700cursive" "bolditalic"  "blackitalic" "boldcursive"  "blackcursive"]
                                    :else
                                    ["bolditalic" "700italic" "blackitalic" "boldcursive" "700cursive" "blackcursive"])
        is-bold?                  (fn [variant-id] (some #(str/includes? variant-id %) bold-options))
        is-italic?                (fn [variant-id] (some #(str/includes? variant-id %) italic-options))
        font-id                   (:font-id text-values)
        fonts                     (deref fonts/fontsdb)
        font                      (get fonts font-id)
        variants                  (map :id (:variants font))
        choose-regular            (fn [] (first-intersection variants regular-options))
        choose-bold               (fn [] (first-intersection variants bold-options))
        choose-italic             (fn [] (first-intersection variants italic-options))
        choose-bold-italic        (fn [] (or (first-intersection variants bold-italic-options) (choose-bold)))
        choose-italic-bold        (fn [] (or (first-intersection variants bold-italic-options) (choose-italic)))

        new-variant               (let [toggle-bold?   (= variant-id "bold")
                                        toggle-italic? (= variant-id "italic")
                                        bold?          (is-bold? current-variant)
                                        italic?        (is-italic? current-variant)]
                                    (cond
                                      (and toggle-bold? bold? italic?) ;; it is bold+italic, set it to italic
                                      (choose-italic)

                                      (and toggle-bold? bold? (not italic?)) ;; it is bold, set it to regular
                                      (choose-regular)

                                      (and toggle-bold? (not bold?) italic?) ;; it is italic, set it to bold+italic
                                      (choose-bold-italic)

                                      (and toggle-bold? (not bold?) (not italic?)) ;; it is regular, set it to bold
                                      (choose-bold)

                                      (and toggle-italic? bold? italic?) ;; it is bold+italic, set it to bold
                                      (choose-bold)

                                      (and toggle-italic? bold? (not italic?)) ;; it is bold, set it to italic+bold
                                      (choose-italic-bold)

                                      (and toggle-italic? (not bold?) italic?) ;; it is italic, set it to regular
                                      (choose-regular)

                                      (and toggle-italic? (not bold?) (not italic?)) ;; it is regular, set it to italic
                                      (choose-italic)))

        new-weight                (->> (:variants font)
                                       (filter #(= (:id %) new-variant))
                                       first
                                       :weight)]
    {:font-variant-id new-variant,
     :font-weight new-weight}))

(defn- update-attrs [shape props]
  (let [state-map     (deref refs/workspace-editor-state)
        editor-state  (get state-map (:id shape))
        text-values   (d/merge
                       (dwt/current-root-values
                        {:shape shape
                         :attrs dwt/root-attrs})
                       (dwt/current-paragraph-values
                        {:editor-state editor-state
                         :shape shape
                         :attrs dwt/paragraph-attrs})
                       (dwt/current-text-values
                        {:editor-state editor-state
                         :shape shape
                         :attrs dwt/text-attrs}))
        font-size      (d/parse-double (:font-size text-values))
        line-height    (d/parse-double (:line-height text-values))
        letter-spacing (d/parse-double (:letter-spacing text-values))
        props (cond
                (:font-size-inc props)
                {:font-size (str (inc font-size))}
                (:font-size-dec props)
                {:font-size (str (dec font-size))}
                (:line-height-inc props)
                {:line-height (str (+ line-height 0.1))}
                (:line-height-dec props)
                {:line-height (str (- line-height 0.1))}
                (:letter-spacing-inc props)
                {:letter-spacing (str (+ letter-spacing 0.1))}
                (:letter-spacing-dec props)
                {:letter-spacing (str (- letter-spacing 0.1))}
                (= (:text-decoration props) "underline") ;;toggle
                (if (= (:text-decoration text-values) "underline")
                  {:text-decoration "none"}
                  props)
                (= (:text-decoration props) "line-through") ;;toggle
                (if (= (:text-decoration text-values) "line-through")
                  {:text-decoration "none"}
                  props)
                (:font-variant-id props)
                (generate-variant-props text-values (:font-variant-id props))
                :else props)]

    (when shape
      (st/emit! (dwt/update-attrs (:id shape) props)))))

(defn- update-attrs-when-no-readonly [props]
  (let [read-only? (deref refs/workspace-read-only?)
        shapes     (deref refs/selected-objects)
        shape      (first shapes)]
    (when (and (not read-only?)
               (= 1 (count shapes))
               (= (:type shape) :text))
      (update-attrs shape props))))

(def shortcuts
  {:align-left    {:tooltip (ds/meta (ds/alt "l"))
                   :command (ds/c-mod "alt+l")
                   :subsections [:text-editor]
                   :fn #(update-attrs-when-no-readonly {:text-align "left"})}
   :align-right   {:tooltip (ds/meta (ds/alt "r"))
                   :command (ds/c-mod "alt+r")
                   :subsections [:text-editor]
                   :fn #(update-attrs-when-no-readonly {:text-align "right"})}
   :align-center  {:tooltip (ds/meta (ds/alt "t"))
                   :command (ds/c-mod "alt+t")
                   :subsections [:text-editor]
                   :fn #(update-attrs-when-no-readonly {:text-align "center"})}
   :align-justify {:tooltip (ds/meta (ds/alt "j"))
                   :command (ds/c-mod "alt+j")
                   :subsections [:text-editor]
                   :fn #(update-attrs-when-no-readonly {:text-align "justify"})}

   :underline     {:tooltip (ds/meta "u")
                   :command (ds/c-mod "u")
                   :subsections [:text-editor]
                   :fn #(update-attrs-when-no-readonly {:text-decoration "underline"})}

   :line-through  {:tooltip (ds/alt (ds/meta-shift "5"))
                   :command "alt+shift+5"
                   :subsections [:text-editor]
                   :fn #(update-attrs-when-no-readonly {:text-decoration "line-through"})}

   :font-size-inc {:tooltip (ds/meta-shift ds/up-arrow)
                   :command (ds/c-mod "shift+up")
                   :subsections [:text-editor]
                   :fn #(update-attrs-when-no-readonly {:font-size-inc true})}

   :font-size-dec {:tooltip (ds/meta-shift ds/down-arrow)
                   :command (ds/c-mod "shift+down")
                   :subsections [:text-editor]
                   :fn #(update-attrs-when-no-readonly {:font-size-dec true})}

   :line-height-inc {:tooltip (ds/alt-shift ds/up-arrow)
                     :command (ds/a-mod "shift+up")
                     :subsections [:text-editor]
                     :fn #(update-attrs-when-no-readonly {:line-height-inc true})}

   :line-height-dec {:tooltip (ds/alt-shift ds/down-arrow)
                     :command (ds/a-mod "shift+down")
                     :subsections [:text-editor]
                     :fn #(update-attrs-when-no-readonly {:line-height-dec true})}

   :letter-spacing-inc {:tooltip (ds/alt ds/up-arrow)
                        :command (ds/a-mod "up")
                        :subsections [:text-editor]
                        :fn #(update-attrs-when-no-readonly {:letter-spacing-inc true})}

   :letter-spacing-dec {:tooltip (ds/alt ds/down-arrow)
                        :command (ds/a-mod "down")
                        :subsections [:text-editor]
                        :fn #(update-attrs-when-no-readonly {:letter-spacing-dec true})}

   :bold     {:tooltip (ds/meta "b")
              :command (ds/c-mod "b")
              :subsections [:text-editor]
              :fn #(update-attrs-when-no-readonly {:font-variant-id "bold"})}

   :italic     {:tooltip (ds/meta "i")
                :command (ds/c-mod "i")
                :subsections [:text-editor]
                :fn #(update-attrs-when-no-readonly {:font-variant-id "italic"})}})



