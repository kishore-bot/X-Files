//! A small element tree for XML parts that need random access (slides,
//! layouts, masters, themes). Built from [`XmlReader`], so it inherits its
//! nesting limit, entity rules and cancellation; callers bound the input size.

use document_core::{CancelToken, Result};

use crate::xml::{Tag, Xml, XmlReader};

pub(crate) struct Node {
    pub tag: Tag,
    pub children: Vec<Node>,
    /// Text directly inside this element. From [`subtree`], only the text
    /// before its first child; the rest is in each child's [`Self::tail`].
    pub text: String,
    /// From [`subtree`]: text after this element, before its next sibling.
    pub tail: String,
}

impl Node {
    pub fn name(&self) -> &str {
        &self.tag.name
    }

    pub fn attr(&self, name: &str) -> Option<&str> {
        self.tag.attr(name)
    }

    pub fn prefixed_attr(&self, name: &str) -> Option<&str> {
        self.tag.prefixed_attr(name)
    }

    pub fn tag_attrs(&self) -> impl Iterator<Item = (&str, &str)> {
        self.tag.attrs()
    }

    pub fn attr_f32(&self, name: &str) -> Option<f32> {
        self.attr(name).and_then(|v| v.parse().ok())
    }

    pub fn child(&self, name: &str) -> Option<&Node> {
        self.children.iter().find(|c| c.name() == name)
    }

    pub fn children_named<'a>(&'a self, name: &'a str) -> impl Iterator<Item = &'a Node> + 'a {
        self.children.iter().filter(move |c| c.name() == name)
    }

    /// Follows a path of child names, e.g. `["cSld", "spTree"]`.
    pub fn path(&self, names: &[&str]) -> Option<&Node> {
        names.iter().try_fold(self, |node, name| node.child(name))
    }

    /// First descendant (depth-first) with this name.
    pub fn find(&self, name: &str) -> Option<&Node> {
        self.children.iter().find_map(|c| if c.name() == name { Some(c) } else { c.find(name) })
    }
}

/// Parses `bytes` into a tree; returns the root element.
pub(crate) fn parse(bytes: &[u8], cancel: &CancelToken) -> Result<Option<Node>> {
    let mut xml = XmlReader::new(bytes, cancel.clone());
    let mut stack: Vec<Node> = Vec::new();
    let mut root = None;
    loop {
        match xml.next()? {
            Xml::Start(tag) => stack.push(Node { tag, children: Vec::new(), text: String::new(), tail: String::new() }),
            Xml::Empty(tag) => {
                let node = Node { tag, children: Vec::new(), text: String::new(), tail: String::new() };
                match stack.last_mut() {
                    Some(parent) => parent.children.push(node),
                    None => root = Some(node),
                }
            }
            Xml::Text(text) => {
                if let Some(node) = stack.last_mut() {
                    node.text.push_str(&text);
                }
            }
            Xml::End(_) => {
                if let Some(node) = stack.pop() {
                    match stack.last_mut() {
                        Some(parent) => parent.children.push(node),
                        None => root = Some(node),
                    }
                }
            }
            Xml::Eof => {
                // Unclosed elements (truncated XML) are attached as they are.
                while let Some(node) = stack.pop() {
                    match stack.last_mut() {
                        Some(parent) => parent.children.push(node),
                        None => root = Some(node),
                    }
                }
                return Ok(root);
            }
        }
    }
}

/// Reads the element `start` has just opened, through its end, keeping the
/// order of text and child elements (see [`Node::tail`]), as mixed content
/// such as ODF paragraphs needs. Embedded binary data is skipped.
pub(crate) fn subtree<R: std::io::BufRead>(xml: &mut XmlReader<R>, start: Tag) -> Result<Node> {
    let mut stack = vec![Node { tag: start, children: Vec::new(), text: String::new(), tail: String::new() }];
    loop {
        match xml.next()? {
            Xml::Start(tag) if tag.name == "binary-data" => xml.skip_element()?,
            Xml::Start(tag) => stack.push(Node { tag, children: Vec::new(), text: String::new(), tail: String::new() }),
            Xml::Empty(tag) => {
                let node = Node { tag, children: Vec::new(), text: String::new(), tail: String::new() };
                stack.last_mut().expect("the start is open").children.push(node);
            }
            Xml::Text(text) => {
                let node = stack.last_mut().expect("the start is open");
                match node.children.last_mut() {
                    Some(child) => child.tail.push_str(&text),
                    None => node.text.push_str(&text),
                }
            }
            Xml::End(_) | Xml::Eof => {
                let node = stack.pop().expect("the start is open");
                match stack.last_mut() {
                    Some(parent) => parent.children.push(node),
                    None => return Ok(node),
                }
            }
        }
    }
}
