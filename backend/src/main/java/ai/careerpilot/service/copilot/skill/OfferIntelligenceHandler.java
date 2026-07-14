package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.offer.Offer;
import ai.careerpilot.repo.OfferRepository;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gap B — Offer Intelligence & Salary Negotiation. Backs both {@link CopilotSkill#EXPLAIN_OFFER}
 * (explain a single offer / the salary-intelligence agent's percentiles and negotiation strategy)
 * and {@link CopilotSkill#COMPARE_OFFERS} (compare the user's offers), same reuse pattern as
 * {@code SubmissionStatusHandler} backing two skills in {@code CopilotSkillRouter}.
 *
 * <p><b>Hard constraint:</b> this handler must never let the model frame output as legal or
 * financial advice — the system prompt says so explicitly, and the context block is purely
 * factual (persisted numbers from {@link Offer} rows, no synthesized recommendations beyond what
 * the offer rows already contain).
 */
@Component
public class OfferIntelligenceHandler extends AbstractSkillHandler {

    private final OfferRepository offers;

    public OfferIntelligenceHandler(CareerContextRetriever retriever, OfferRepository offers) {
        super(retriever);
        this.offers = offers;
    }

    // Router wires this handler under both EXPLAIN_OFFER and COMPARE_OFFERS; skill() reports the
    // primary one (mirrors SubmissionStatusHandler's SUBMISSION_STATUS primary skill).
    @Override public CopilotSkill skill() { return CopilotSkill.EXPLAIN_OFFER; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            List<Offer> userOffers = offers.findByUserIdOrderByCreatedAtDesc(context.user().userId());
            context.offers(userOffers);
            log.debug("Offer intelligence context assembled: {} offers", userOffers.size());
        } catch (Exception e) {
            log.warn("Could not load offers for offer intelligence: {}", e.getMessage());
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, a factual compensation-data assistant.

            TASK — Offer Intelligence: Using ONLY the offer data and market percentiles in CONTEXT,
            explain the candidate's offer(s) and/or compare multiple offers component-by-component
            (base salary, bonus, RSU/equity value, joining bonus, total comp).

            STRICT RULES:
            - NEVER frame anything you say as legal advice or financial advice. Do not use phrases
              like "you should accept", "legally you are entitled to", or "financially this is the
              right choice". You are not a lawyer or a licensed financial advisor.
            - Present comparisons and percentile positioning FACTUALLY — state the numbers and the
              deltas, and describe what the negotiation_strategy/leverage_points fields already say,
              without inventing new legal or financial claims.
            - It is fine to restate the offer's own persisted negotiation_strategy/leverage_points
              bullets and to point out which offer is numerically higher on which component. It is
              NOT fine to advise the candidate what to do, sign, or negotiate beyond what is already
              recorded in CONTEXT.
            - If the candidate asks for legal or financial advice directly, decline and suggest they
              consult a qualified professional.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        List<Offer> userOffers = context.offers();
        StringBuilder sb = new StringBuilder("CONTEXT — Offer Intelligence (factual, informational only)\n\n");

        if (userOffers == null || userOffers.isEmpty()) {
            sb.append("No offers on file for this user.\n");
            return sb.toString();
        }

        int i = 1;
        for (Offer o : userOffers) {
            sb.append("OFFER ").append(i++).append("\n");
            sb.append("Company: ").append(nullSafe(o.getCompanyName())).append("\n");
            sb.append("Source: ").append(nullSafe(o.getSource())).append("\n");
            sb.append("Currency: ").append(nullSafe(o.getCurrency())).append("\n");
            sb.append("Base salary: ").append(orNa(o.getBaseSalary())).append("\n");
            sb.append("Bonus: ").append(orNa(o.getBonus())).append("\n");
            sb.append("RSU/equity value: ").append(orNa(o.getRsuValue())).append("\n");
            sb.append("Joining bonus: ").append(orNa(o.getJoiningBonus())).append("\n");
            if (o.getMarketP25() != null || o.getMarketP50() != null || o.getMarketP75() != null || o.getMarketP90() != null) {
                sb.append("Market percentiles (p25/p50/p75/p90): ")
                        .append(orNa(o.getMarketP25())).append(" / ")
                        .append(orNa(o.getMarketP50())).append(" / ")
                        .append(orNa(o.getMarketP75())).append(" / ")
                        .append(orNa(o.getMarketP90())).append("\n");
            }
            if (o.getNegotiationStrategy() != null && !o.getNegotiationStrategy().isBlank()) {
                sb.append("Recorded negotiation strategy:\n").append(o.getNegotiationStrategy()).append("\n");
            }
            if (o.getLeveragePoints() != null && !o.getLeveragePoints().isBlank()) {
                sb.append("Recorded leverage points: ").append(truncate(o.getLeveragePoints(), MAX_SKILL_CHARS)).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String orNa(Object v) {
        return v == null ? "n/a" : v.toString();
    }
}
