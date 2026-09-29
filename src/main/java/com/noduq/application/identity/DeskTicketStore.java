package com.noduq.application.identity;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
class DeskTicketStore {

	private static final int CAP = 8_000;

	private final ConcurrentHashMap<UUID, DeskTicket> tickets = new ConcurrentHashMap<>();

	void put(DeskTicket ticket, Instant now) {
		if (tickets.size() >= CAP) {
			evictExpired(now);
		}
		tickets.put(ticket.id(), ticket);
	}

	Optional<DeskTicket> find(UUID id) {
		return Optional.ofNullable(tickets.get(id));
	}

	void replace(DeskTicket ticket) {
		tickets.put(ticket.id(), ticket);
	}

	void remove(UUID id) {
		tickets.remove(id);
	}

	void evictExpired(Instant now) {
		tickets.entrySet().removeIf(entry -> entry.getValue().expired(now));
	}

	int size() {
		return tickets.size();
	}
}
