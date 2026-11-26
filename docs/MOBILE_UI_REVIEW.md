# Mobile UI review

This pass reviewed the Compose screens and navigation in source. The device
walkthrough remains pending. The supplied screenshots informed the launch and
reader changes.

| Area | Findings and changes |
| --- | --- |
| Startup | The animated mascot and separate Compose intro competed for attention and delayed the transition. The platform splash now displays the shared Xfiles mark on the window background. External file intents enter the reader directly. |
| Library, Recent, Bookmarks | The floating open button and tabs overlap the list by design. The list now reserves the measured height of the portrait controls. A side rail supports more landscape widths, and the first folder scan has its own loading state. |
| PDF and images | The content is the primary surface. The stronger reader bar and labelled 48 dp controls stay distinguishable over white pages and dark photos. PDF navigation and zoom wrap as groups on narrower layouts. The thumbnail sheet stays separate from the anchored settings menu. |
| Word and rich documents | A paired page and foreground palette replaces a mix of glass and theme defaults. Headings, links, quote cards, and both table paths use page colors. Authored text stays readable against the actual surface. |
| Spreadsheets | Frozen labels, cell borders and selection share the reader palette. Native LazyMatrix panes use an opaque page behind cells. Authored fills are preserved, and the ink and selection outline are adjusted when their contrast is low. The grid dimensions grow with font scale. |
| Plain text, code, JSON, HTML | Code follows the selected reading theme rather than the system theme. Match highlights have a defined foreground. Existing list and grid state stays outside the theme choice. Web documents preserve their authored presentation in Standard and Original, and fixed styles preserve both scroll axes on reload. |
| EPUB, slides, archives | EPUB shares the reader controls while keeping its own book themes. Presentation and archive navigation remain format-specific. Check their empty, error, and selection affordances on the device walkthrough. |
| Audio and video | The top bar, selected menu, and playback sheets use the shared controls. Sheet contents can scroll in short landscape windows. System bars use light icons when the player is on black. Verify scrubbing and subtitle readability against upright and landscape video. |
| Tools and editor | Tool layouts account for the keyboard. The editor gives its title and Save copy their own row, with history actions and compact encoding and line-ending menus below. Verify the editable area remains reachable with the keyboard open at high font scale. |
| Fily | The voice state and close action stay visible while long replies and settings scroll. Check model setup, permission denial, and Back from settings with TalkBack enabled. |
| Settings and About | Appearance, Fily model management, search maintenance, and diagnostics have separate settings sections. Model actions remain reachable when Fily is off. About presents version, build, release notes, feedback, source, licenses, and privacy. A build without Firebase explains why cloud diagnostics are unavailable. |

## Device walkthrough

Use a small phone in portrait and landscape. Repeat with Light and Dark selected
in Xfiles, large system fonts, TalkBack, and Android's Remove animations option.

- Launch cold, resume warm, and open a file from another app. Check that the
  first reader frame follows the system splash and the file starts opening
  without a library detour.
- Review all three library tabs with no folders, during scanning, and with
  a revoked folder grant. Check that the last row clears the floating controls.
- Open a white PDF, dark photo, DOCX with authored colors and links, wide XLSX
  with frozen and merged cells, plain text, syntax-highlighted code, and video.
  Switch themes mid-file. Verify scroll position, search matches, selection
  handles and cell outlines.
- Open reader menus, bottom sheets, and Fily in succession. Confirm Back
  dismisses the active surface first and TalkBack announces its actions and state.
- Open a converter and the editor with the keyboard present. Check loading,
  empty, save-copy, failure, and cancellation states.
- Open Settings and About with Fily disabled and on a build without Firebase.
  Verify model removal, search-index feedback, and the About links.
