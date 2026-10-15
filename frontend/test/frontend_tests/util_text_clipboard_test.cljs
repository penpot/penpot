;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util-text-clipboard-test
  "Clipboard HTML and plain text read into paste fragments. Only emphasis
   survives; the documents come from jsdom since the runner has no DOM."
  (:require
   ["jsdom" :refer [JSDOM]]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.util.text.clipboard :as clipboard]
   [cljs.test :as t :include-macros true]))

(defn- html->fragment
  [html]
  (let [window (.-window (JSDOM. ""))
        parser (new (.-DOMParser window))]
    (clipboard/document->fragment (.parseFromString parser html "text/html"))))

(defn- paragraph [& runs]
  {:attrs {} :children (vec runs)})

;; HTML runs carry every emphasis attr, so the source decides it.
(def ^:private no-emphasis
  {:font-weight "400" :font-style "normal" :text-decoration "none" :text-transform "none"})

(defn- run
  ([text] (run text {}))
  ([text attrs] {:text text :attrs (merge no-emphasis attrs)}))

(defn- plain-run
  [text]
  {:text text :attrs {}})

(def ^:private bold {:font-weight "700"})
(def ^:private italic {:font-style "italic"})

(def ^:private google-docs-html
  (str "<meta charset=\"utf-8\">"
       "<b style=\"font-weight:normal;\" id=\"docs-internal-guid-1a2b3c\">"
       "<p dir=\"ltr\" style=\"line-height:1.38;margin-top:0pt;margin-bottom:0pt;\">"
       "<span style=\"font-size:11pt;font-family:Arial,sans-serif;color:#000000;font-weight:400;font-style:normal;text-decoration:none;white-space:pre;white-space:pre-wrap;\">Hello </span>"
       "<span style=\"font-size:11pt;font-family:Arial,sans-serif;color:#000000;font-weight:700;font-style:normal;text-decoration:none;white-space:pre;white-space:pre-wrap;\">bold</span>"
       "<span style=\"font-size:11pt;font-family:Arial,sans-serif;color:#000000;font-weight:400;font-style:italic;text-decoration:none;white-space:pre;white-space:pre-wrap;\"> and italic</span>"
       "</p><br>"
       "<p dir=\"ltr\" style=\"line-height:1.38;margin-top:0pt;margin-bottom:0pt;\">"
       "<span style=\"font-size:11pt;font-family:Arial,sans-serif;color:#000000;font-weight:400;white-space:pre-wrap;\">Second</span>"
       "</p></b>"))

(def ^:private word-html
  (str "<html xmlns:o=\"urn:schemas-microsoft-com:office:office\">"
       "<head><style><!-- p.MsoNormal {margin:0cm;} --></style></head>"
       "<body lang=EN-US><!--StartFragment-->"
       "<p class=MsoNormal><span lang=EN-US>Plain <b>bold</b></span><o:p></o:p></p>\n"
       "<p class=MsoNormal><o:p>&nbsp;</o:p></p>\n"
       "<p class=MsoNormal><i><span lang=EN-US>italic</span></i><o:p></o:p></p>"
       "<!--EndFragment--></body></html>"))

(def ^:private web-page-html
  (str "<meta charset='utf-8'>"
       "<h2 style=\"color: rgb(0, 0, 0); font-family: Georgia; font-size: 24px;\">Title</h2>"
       "<p style=\"color: rgb(0, 0, 0); font-size: 16px; font-weight: 400;\">"
       "Some <strong>strong</strong> and "
       "<a href=\"https://example.com\" style=\"text-decoration: underline;\">a link</a>.</p>"
       "<ul>\n  <li>One</li>\n  <li>Two</li>\n</ul>"))

(t/deftest google-docs-keeps-emphasis
  (t/testing "the font-weight:normal wrapper does not make the whole paste bold"
    (t/is (= [(paragraph (run "Hello ") (run "bold" bold) (run " and italic" italic))
              (paragraph)
              (paragraph (run "Second"))]
             (html->fragment google-docs-html)))))

(t/deftest word-keeps-emphasis
  (t/testing "Word markup and its empty &nbsp; paragraphs map to plain paragraphs"
    (t/is (= [(paragraph (run "Plain ") (run "bold" bold))
              (paragraph)
              (paragraph (run "italic" italic))]
             (html->fragment word-html)))))

(t/deftest web-page-drops-fonts-colors-links-and-list-markers
  (t/testing "headings are bold, links lose their underline, list items become paragraphs"
    (t/is (= [(paragraph (run "Title" bold))
              (paragraph (run "Some ") (run "strong" bold) (run " and a link."))
              (paragraph (run "One"))
              (paragraph (run "Two"))]
             (html->fragment web-page-html)))))

(t/deftest font-weight-threshold
  (t/testing "600 and above is bold"
    (t/is (= [(paragraph (run "semi" bold))]
             (html->fragment "<span style=\"font-weight:600\">semi</span>"))))

  (t/testing "light weights are ignored"
    (t/is (= [(paragraph (run "light"))]
             (html->fragment "<span style=\"font-weight:300\">light</span>")))))

(t/deftest decoration-and-transform
  (t/testing "underline wins over line-through, as a span holds one decoration"
    (t/is (= [(paragraph (run "both" {:text-decoration "underline"}))]
             (html->fragment "<u><s>both</s></u>"))))

  (t/testing "line-through alone is kept"
    (t/is (= [(paragraph (run "gone" {:text-decoration "line-through"}))]
             (html->fragment "<del>gone</del>"))))

  (t/testing "text-transform is kept"
    (t/is (= [(paragraph (run "loud" {:text-transform "uppercase"}))]
             (html->fragment "<span style=\"text-transform:uppercase\">loud</span>")))))

(t/deftest whitespace
  (t/testing "collapsible whitespace collapses and is trimmed at line ends"
    (t/is (= [(paragraph (run "a b"))]
             (html->fragment "<p>  a \n   b  </p>"))))

  (t/testing "&nbsp; is kept as a space"
    (t/is (= [(paragraph (run "a  b"))]
             (html->fragment "<p>a&nbsp;&nbsp;b</p>"))))

  (t/testing "pre keeps spaces and splits lines into paragraphs"
    (t/is (= [(paragraph (run "line 1"))
              (paragraph (run "  line 2"))]
             (html->fragment "<pre>line 1\n  line 2</pre>")))))

(t/deftest line-breaks-and-empty-paragraphs
  (t/testing "br starts a new paragraph"
    (t/is (= [(paragraph (run "a")) (paragraph (run "b"))]
             (html->fragment "a<br>b"))))

  (t/testing "runs of empty lines collapse into one"
    (t/is (= [(paragraph (run "a")) (paragraph) (paragraph (run "b"))]
             (html->fragment "<p>a</p><br><br><br><p>b</p>"))))

  (t/testing "empty lines at both ends are dropped"
    (t/is (= [(paragraph (run "a"))]
             (html->fragment "<br><p>a</p><br><br>")))))

(t/deftest tables
  (t/testing "each row is a paragraph with its cells joined by a tab"
    (t/is (= [(paragraph (run "a\tb")) (paragraph (run "c\td"))]
             (html->fragment
              "<table><tr><td>a</td><td> b</td></tr><tr><td>c</td><td>d</td></tr></table>")))))

(t/deftest hidden-content-is-skipped
  (t/testing "style, script and display:none give no text"
    (t/is (= [(paragraph (run "shown"))]
             (html->fragment
              "<style>p{color:red}</style><p>shown</p><script>x()</script><p style=\"display:none\">hidden</p>"))))

  (t/testing "elements outside the allowlist are skipped with their text"
    (t/is (= [(paragraph (run "kept"))]
             (html->fragment
              "<p>kept<my-widget>custom</my-widget><button>Buy</button><textarea>typed</textarea></p>"))))

  (t/testing "HTML without text gives no fragment"
    (t/is (nil? (html->fragment "<img src=\"x.png\">")))))

(t/deftest plain-text
  (t/testing "each line is a paragraph and CRLF counts as one break"
    (t/is (= [(paragraph (plain-run "a")) (paragraph) (paragraph (plain-run "b"))]
             (clipboard/text->fragment "a\r\n\r\nb"))))

  (t/testing "empty text gives no fragment"
    (t/is (nil? (clipboard/text->fragment "")))))

(t/deftest without-overrides
  (t/testing "external HTML keeps its text and paragraphs but no emphasis"
    (t/is (= [(paragraph (plain-run "Hello bold and italic"))
              (paragraph)
              (paragraph (plain-run "Second"))]
             (clipboard/without-overrides (html->fragment google-docs-html)))))

  (t/testing "empty paragraphs stay empty"
    (t/is (= [(paragraph)]
             (clipboard/without-overrides [(paragraph)])))))

;; --- Penpot payload

(def ^:private file-id (uuid/next))
(def ^:private team-id (uuid/next))

(defn- text-content [& spans]
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph" :text-align "center" :children (vec spans)}]}]})

(def ^:private gradient-span
  {:text "Ñandú 😀 \"<b>\""
   :font-id "custom-6e2c1a5e-1c1e-4b54-9c64-2a8c5f1a4c11"
   :font-family "Brand Sans"
   :font-variant-id "bold"
   :font-weight "700"
   :font-style "italic"
   :fills [{:fill-color-gradient {:type :linear :start-x 0 :start-y 0 :end-x 1 :end-y 1 :width 1
                                  :stops [{:color "#ff0000" :opacity 1 :offset 0}
                                          {:color "#0000ff" :opacity 1 :offset 1}]}
            :fill-opacity 1}]})

(t/deftest payload-round-trip
  (let [content (text-content gradient-span)
        hidden  (clipboard/payload->html content file-id team-id)]
    (t/testing "the payload decodes back to the same content, file and team"
      (t/is (= {:type :copied-text :version 1 :file-id file-id :team-id team-id :content content}
               (clipboard/html->payload (str "<meta charset=\"utf-8\">" hidden "Hello")))))

    (t/testing "it survives the markup browsers add around it"
      (let [safari (-> hidden
                       (.replace "></span>" " style=\"color: rgb(0, 0, 0); font-size: medium;\"></span>"))]
        (t/is (= content
                 (:content (clipboard/html->payload
                            (str "<head><meta charset=\"UTF-8\"></head>" safari "Hello")))))))))

(t/deftest html-without-payload
  (t/testing "HTML without a payload, or with a broken one, gives nil"
    (t/is (nil? (clipboard/html->payload "<p>Hello</p>")))
    (t/is (nil? (clipboard/html->payload "<span data-penpot-text=\"bm90IHRyYW5zaXQ=\"></span>")))
    (t/is (nil? (clipboard/html->payload nil)))))

;; --- Clean-up

(def ^:private image-fill
  {:fill-image {:id (uuid/next) :width 10 :height 10 :mtype "image/png"} :fill-opacity 1})

(def ^:private red-fill
  {:fill-color "#ff0000" :fill-opacity 1})

(defn- spans-of [content]
  (-> content :children first :children first :children))

(t/deftest clean-content
  (let [library-id (uuid/next)
        typography {:typography-ref-file library-id :typography-ref-id (uuid/next)}
        content    (text-content (merge gradient-span typography)
                                 {:text "img" :fills [image-fill red-fill]}
                                 {:text "only img" :fills [image-fill]})
        same       {:valid-file-ids #{library-id} :same-team? true :same-file? true}]

    (t/testing "in the same file nothing changes"
      (t/is (= content (clipboard/clean-content content same))))

    (t/testing "typographies from unreachable files are dropped"
      (t/is (nil? (-> (clipboard/clean-content content (assoc same :valid-file-ids #{}))
                      (spans-of)
                      (first)
                      :typography-ref-id))))

    (t/testing "custom fonts from another team become the default font, at the closest variant"
      (let [span (first (spans-of (clipboard/clean-content content (assoc same :same-team? false))))]
        (t/is (= {:font-id "sourcesanspro" :font-family "sourcesanspro"
                  :font-variant-id "bolditalic" :font-weight "700" :font-style "italic"}
                 (select-keys span [:font-id :font-family :font-variant-id :font-weight :font-style])))
        (t/is (nil? (:typography-ref-id span)))))

    (t/testing "builtin and google fonts stay in another team"
      (let [content (text-content (assoc gradient-span :font-id "gfont-roboto" :font-family "Roboto"))]
        (t/is (= content (clipboard/clean-content content (assoc same :same-team? false))))))

    (t/testing "images from another file are dropped, leaving the default fill when nothing is left"
      (t/is (= [(:fills gradient-span) [red-fill] (txt/get-default-text-fills)]
               (map :fills (spans-of (clipboard/clean-content content (assoc same :same-file? false)))))))))

(t/deftest replace-custom-fonts
  (t/testing "only custom fonts become the default font"
    (let [content (text-content gradient-span (assoc gradient-span :font-id "gfont-roboto" :font-family "Roboto"))]
      (t/is (= ["sourcesanspro" "gfont-roboto"]
               (map :font-id (spans-of (clipboard/replace-custom-fonts content))))))))
