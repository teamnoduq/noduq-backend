package com.noduq.domain.identity.port;

import com.noduq.domain.identity.EmployeeCode;

public interface EmployeeCodeHasher {

	String lookup(EmployeeCode code);

	String hash(EmployeeCode code);

	boolean matches(EmployeeCode code, String codeHash);
}
