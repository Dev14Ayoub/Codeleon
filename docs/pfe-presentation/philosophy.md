# Engineering Cartography
## Design philosophy for the Codeleon PFE presentation document

A design movement for technical documents that demand both **rigor** and
**craftsmanship**. Not an art object — a meticulously made instrument that
communicates technical scope, decisions, and trade-offs to a careful reader.

---

### The manifesto

Engineering Cartography treats a technical document the way a master cartographer
treats a map: every choice deliberate, every measurement honest, every
ornament earned. Information is not decorated; it is **placed**. The
reader feels the labour without being shown the sweat.

A page is a quiet, dark canvas — the same indigo–cyan palette the product
itself uses — onto which evidence is laid in restrained, confident strokes.
There is no marketing tone, no flourish for its own sake. The work earns
trust by being precise where precision matters and silent everywhere else.

### Space and form

Generous negative space, never accidental. Wide outer margins that frame
each page like a museum mat. A single column of body text at a comfortable
~62 character measure; technical figures break out into a wider sidebar
when they need air. The grid is invisible but it is there, and the eye
feels its presence.

Vertical rhythm is a 4-pt baseline carried with discipline across pages.
Section breaks are felt through space, not through ornament — no heavy
rules, no decorative dividers. When a horizontal line appears, it is
hair-thin and it earns its place.

### Color and material

A muted, deliberate palette: zinc-950 background, zinc-100 type, with
two and only two accent colours — `#6366F1` (signature indigo) for emphasis
and `#06B6D4` (cyan) for technical metadata, key numbers, and code accents.
Success / warn / danger appear sparingly inside dedicated callouts and
never decoratively. Colour is signal, never style.

Code lives in `Geist Mono` against a barely-darker panel; prose in `Geist`
or a comparable modern sans. Headings are heavy but small — confidence,
not shouting. Diagrams are drawn in SVG with the same restraint: outlines,
not fills; one stroke weight; the same two accent colours; never a
gradient.

### Scale, rhythm, composition

Hierarchy comes from **type weight and space**, not size. The reader's
eye moves through the document the same way it moves through a careful
research paper: page number whispered in a corner, section header confident
but small, body text the centre of gravity. Numbers — the ones that
matter for evidence (test counts, commit counts, supported languages) —
are allowed to be large, set in cyan, and given the silence around them
that lets them register.

Architecture diagrams are treated with cartographer's restraint: every
node a labelled rectangle, every edge a single thin stroke, no shadows,
no rounded soft corners pretending the system is gentler than it is. The
geometry tells the story.

### Visual hierarchy and rigour

Each page does **one thing**. A cover bears a single typographic gesture.
An executive summary is a paragraph and three numbers. An architecture
spread is a diagram with a caption. A problem-and-solution page is a
short narrative beside a code excerpt or a small chart. The reader never
has to negotiate two competing focal points on a page.

Captions and labels are tiny, set in the cyan accent, monospace, in
small-caps where helpful — the way a technical drawing labels its parts.
Body text never argues with the diagram; the diagram never apologises
for the body text.

### Craftsmanship

This document must read as the product of patient, expert hands. The
reader should sense — without it ever being announced — that every margin
was measured, every kerning pair checked, every diagram redrawn. The
absence of mistakes is the loudest signal. A senior engineer should look
at it and recognise the work of a peer; a professor should look at it
and recognise the discipline of someone who has gone the distance. The
final pass is not for adding more — it is for taking the last unnecessary
mark away.

The page that earns the reader's trust is the page that knows when to
stop.
