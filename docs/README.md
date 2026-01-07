# Atomikos Documentation

This directory contains technical documentation and analysis for the Atomikos TransactionsEssentials project.

## Available Documents

### [ATOMIKOS_XA_POOLING_ANALYSIS.md](./ATOMIKOS_XA_POOLING_ANALYSIS.md)

**Comprehensive Analysis: Using Atomikos XA Connection Pooling in Isolation**

This document provides an in-depth technical analysis of the Atomikos XA connection pooling architecture, answering the question: *"Can you use only the XA connection pooling of Atomikos in isolation?"*

**Contents:**
- Executive summary with clear answer to isolation feasibility
- Detailed architecture diagrams using Mermaid
- Component-by-component analysis of pooling classes
- Dependency analysis and coupling points
- Three practical options for using Atomikos pooling
- Connection pool workflow with sequence diagrams
- Performance characteristics and memory footprint
- Comparison with other XA pooling solutions (Bitronix, Narayana)
- Detailed explanation of how Atomikos connection pooling works
- Practical recommendations for different use cases
- Code examples and configuration samples

**Target Audience:**
- Developers evaluating Atomikos for XA connection pooling
- Database proxy implementers needing XA support
- Architects assessing transaction management options
- Anyone looking to understand XA connection pooling architecture

**Key Findings:**
- Atomikos pooling is tightly coupled with transaction management
- Full isolation is impractical (would require 5,000-8,000 LOC extraction)
- Recommended approach: Use full Atomikos library for XA, HikariCP for non-XA
- Transaction manager overhead is minimal when not using JTA features

---

## Contributing

If you have suggestions for additional documentation or find errors, please open an issue or submit a pull request.

## License

These documents are provided under the same license as the Atomikos TransactionsEssentials project.
