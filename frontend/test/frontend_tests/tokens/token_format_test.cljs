;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-format-test
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.format :as wtf]
   [cljs.test :as t :include-macros true]))

(t/deftest format-plain-values
  (t/testing "a single value shows as text"
    (t/is (= "8" (wtf/format-token-value "8")))
    (t/is (= "16" (wtf/format-token-value 16)))
    (t/is (= "{color.primary}" (wtf/format-token-value "{color.primary}"))))

  (t/testing "no value shows as empty text"
    (t/is (= "" (wtf/format-token-value nil)))))

(t/deftest format-list-values
  (t/testing "a font family list shows comma separated"
    (t/is (= "Inter, sans-serif"
             (wtf/format-token-value (:value (ctob/make-token :name "font.body" :type :font-family :value ["Inter" "sans-serif"])))))))

(t/deftest format-typography-values
  (t/testing "a composite typography shows one line per property, with its label"
    (let [token (ctob/make-token :name "typo.body"
                                 :type :typography
                                 :value {:font-family ["Inter" "sans-serif"]
                                         :font-size "16px"
                                         :font-weight "bold"
                                         :line-height "1.5"
                                         :letter-spacing "0.05em"
                                         :text-case "uppercase"
                                         :text-decoration "underline"})]
      (t/is (= (str "\n- Font Family: Inter, sans-serif"
                    "\n- Font Size: 16px"
                    "\n- Font Weight: bold"
                    "\n- Line Height: 1.5"
                    "\n- Letter Spacing: 0.05em"
                    "\n- Text Case: uppercase"
                    "\n- Text Decoration: underline")
               (wtf/format-token-value (:value token))))))

  (t/testing "property names given as strings get their label too"
    (t/is (= "\n- Font Size: 16px" (wtf/format-token-value {"font-size" "16px"})))))

(t/deftest format-shadow-values
  (let [shadow-1 {:offset-x "0" :offset-y "2px" :blur "4px" :spread "0" :color "#000000" :inset false}
        shadow-2 {:offset-x "1" :offset-y "8px" :blur "16px" :spread "2px" :color "#ff0000" :inset true}
        lines-1  (str "\n- X: 0"
                      "\n- Y: 2px"
                      "\n- Blur: 4px"
                      "\n- Spread: 0"
                      "\n- Color: #000000"
                      "\n- Inner Shadow: false")
        lines-2  (str "\n- X: 1"
                      "\n- Y: 8px"
                      "\n- Blur: 16px"
                      "\n- Spread: 2px"
                      "\n- Color: #ff0000"
                      "\n- Inner Shadow: true")]

    (t/testing "a shadow shows one line per property, with its label"
      (let [token (ctob/make-token :name "shadow.sm" :type :shadow :value [shadow-1])]
        (t/is (= lines-1 (wtf/format-token-value (:value token))))))

    (t/testing "several shadows show one after the other"
      (let [token (ctob/make-token :name "shadow.md" :type :shadow :value [shadow-1 shadow-2])]
        (t/is (= (str lines-1 "\n" lines-2) (wtf/format-token-value (:value token))))))))
